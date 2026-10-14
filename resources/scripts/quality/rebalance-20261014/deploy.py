"""Reassign six Main Stage-1 slots and one Main refine slot after task drain.

Run prepare, inspect plan.json, then apply on each explicitly listed host.
No direct Redis writes. Private configuration backups stay mode 0600.
"""
import concurrent.futures
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.request

os.umask(0o077)
sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env

ROOT = pathlib.Path('/home/bekzat/rebalance-20261014')
ROOT.mkdir(mode=0o700, exist_ok=True)
PROJECT = 'panako-refine-rebalance-20261014'
JAR = '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
PLANS = {
    '46.224.159.198': (['panako-stage1-priority-20261013-stage1-' + str(n) for n in (1, 2, 3)]
                     + ['panako-stage1-evac-20261013-stage1-1'], 'refine-c-move-20261006-refine-from-main-1'),
    '91.99.52.184': (['panako-stage1-priority-20261013-stage1-1'], 'refine-a-move-20261006-refine-from-main-1'),
    '157.180.26.0': (['panako-stage1-evac-20261013-stage1-1'], 'refine-lease-canary-20261007-refine-canary-1'),
}
host, action = sys.argv[1:]
assert host in PLANS and action in ('prepare', 'apply')
FILES = ['/app/panako.jar', '/opt/java/openjdk/bin/java', '/opt/java/openjdk/lib/server/libjvm.so',
         '/usr/bin/ffmpeg', '/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2', '/lib/x86_64-linux-gnu/libc.so.6']


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def save(name, value):
    f = ROOT / name
    tmp = f.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, indent=2))
    tmp.replace(f)


def run(args, timeout=90):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        save('command-error-private.json', {'args_prefix': args[:2], 'stdout': result.stdout, 'stderr': result.stderr})
        raise RuntimeError('Command failed; protected diagnostic saved')
    return result.stdout.strip()


def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]


def running():
    ids = run(['docker', 'ps', '-q']).split()
    return json.loads(run(['docker', 'inspect', *ids])) if ids else []


def hashes(image):
    output = run(['docker', 'run', '--rm', '--network=none', '--read-only', '--memory=64m', '--cpus=.25',
                  '--entrypoint', 'sha256sum', image, *FILES])
    return {line.split(None, 1)[1]: line.split()[0] for line in output.splitlines()}


def service(template, settings, count):
    hc = template['HostConfig']
    assert template['Config']['Cmd'] in (['SERVER_PORT=8080'], ['SERVER_PORT=8091'], ['SERVER_PORT=8094'])
    assert not template['Mounts']
    assert settings['PANAKO_WORKER_MODE'] == 'refine' and settings['REDIS_ENABLED'].lower() == 'true'
    assert settings['CLICKHOUSE_HOST'] == '10.0.1.2'
    assert settings['MONITOR_PARALLEL_WINDOWS'] == '2' and settings['REDIS_WORKER_THREADS'] == '1'
    assert '3584m' in settings.get('JAVA_TOOL_OPTIONS', '') + settings.get('JDK_JAVA_OPTIONS', '')
    assert hc['Memory'] == 4608 * 1024**2
    return {'image': template['Image'], 'entrypoint': template['Config']['Entrypoint'],
            'command': ['SERVER_PORT=8080'], 'environment': {k: v.replace('$', '$$') for k, v in settings.items()},
            'restart': 'unless-stopped', 'mem_limit': hc['Memory'], 'memswap_limit': hc['MemorySwap'],
            'cpus': hc['NanoCpus'] / 1e9, 'deploy': {'replicas': count},
            'logging': {'driver': hc['LogConfig']['Type'], 'options': hc['LogConfig']['Config']}}


compose = ['docker', 'compose', '-p', PROJECT, '-f', str(ROOT / 'compose-private.json')]
if action == 'prepare':
    assert not (ROOT / 'plan.json').exists(), 'Preserve existing prepared plan'
    names, template_name = PLANS[host]
    stages = [inspect(n) for n in names]
    template = inspect(template_name)
    before = running()
    assert not any((c['Config'].get('Labels') or {}).get('com.docker.compose.project') == PROJECT for c in before)
    for c in stages:
        assert c['State']['Running'] and not c['State']['Paused']
        assert env(c)['PANAKO_WORKER_MODE'] == 'stage1' and env(c)['REDIS_HOST'] == '10.0.0.2'
        assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == JAR
    expected_hashes = hashes(template['Image'])
    assert expected_hashes['/app/panako.jar'] == JAR
    settings = env(template)
    assert settings['REDIS_HOST'] == '10.0.0.2'
    assert Redis(settings).cmd('PING') == 'PONG'
    services = {'refine-main': service(template, settings, len(stages))}
    expected_env = {'refine-main': settings}
    retired = stages[:]
    if host == '91.99.52.184':
        assert template['State']['Running'] and not template['State']['Paused']
        isolated = json.loads((ROOT / 'isolated-source-private.json').read_text())
        iso = env(isolated)
        assert iso['REDIS_HOST'] == '10.0.0.4' and Redis(iso).cmd('PING') == 'PONG'
        assert expected_hashes == json.loads((ROOT / 'isolated-runtime-hashes.json').read_text())
        isolated['Image'] = template['Image']  # Verified identical runtime/application file hashes.
        services['refine-isolated'] = service(isolated, iso, 1)
        expected_env['refine-isolated'] = iso
        retired.append(template)
        agent = pathlib.Path('/home/bekzat/latency-fleet-20261012/drain-agent/drain-agent.jar')
        assert hashlib.sha256(agent.read_bytes()).hexdigest() == '7fb564fca397860a5d6decfcbfe17cda9f4a403d03e48513ed927fc1976ff4a9'
        run(['docker', 'image', 'inspect', 'eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda'])
    backups = {}
    for c in retired:
        labels = c['Config']['Labels']
        file = pathlib.Path(labels['com.docker.compose.project.config_files'].split(',')[-1])
        role = labels['com.docker.compose.service']
        raw = file.read_text()
        doc = json.loads(raw)
        assert set(doc['services']) == {role}
        assert doc['services'][role]['deploy']['replicas'] >= 1
        backups[str(file)] = {'content': raw, 'sha256': hashlib.sha256(raw.encode()).hexdigest(), 'service': role}
    mem = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    retired_ids = {c['Id'] for c in retired}
    others = {c['Name']: c['Id'] for c in before if c['Id'] not in retired_ids}
    after = sum(c['HostConfig']['Memory'] for c in before if c['Id'] not in retired_ids)
    after += sum(s['mem_limit'] * s['deploy']['replicas'] for s in services.values())
    assert after < mem['MemTotal'] * 1024 - 2 * 1024**3
    save('before-private.json', {'retired': retired, 'files': backups, 'others': others})
    save('compose-private.json', {'services': services})
    rendered = json.loads(run(compose + ['config', '--format', 'json']))
    assert all(rendered['services'][k]['environment'] == v for k, v in expected_env.items())
    save('expected-private.json', expected_env)
    save('runtime-hashes.json', expected_hashes)
    plan = {'host': host, 'at': stamp(), 'stage1_to_main_refine': len(stages),
            'main_refine_to_isolated_refine': int(host == '91.99.52.184'), 'reserved_after_gib': after / 1024**3,
            'retire_names': [c['Name'] for c in retired], 'main_db': '10.0.1.2',
            'jar_sha256': JAR, 'prepared': True}
    save('plan.json', plan)
    print(json.dumps(plan))
    sys.exit(0)

assert not (ROOT / 'status.json').exists(), 'Inspect previous operation before retry'
plan = json.loads((ROOT / 'plan.json').read_text())
assert plan['host'] == host
backup = json.loads((ROOT / 'before-private.json').read_text())
expected_env = json.loads((ROOT / 'expected-private.json').read_text())
retired = backup['retired']
for c in retired:
    live = inspect(c['Id'])
    assert live['State']['Running'] and not live['State']['Paused'] and env(live) == env(c)
for filename, entry in backup['files'].items():
    assert hashlib.sha256(pathlib.Path(filename).read_bytes()).hexdigest() == entry['sha256']
state = {'host': host, 'started_at': stamp(), 'phase': 'draining'}
save('status.json', state)


def drain(c):
    settings = env(c)
    refine = settings['PANAKO_WORKER_MODE'] == 'refine'
    stream, group = ('panako.monitor.refine.request', 'panako-worker-refine') if refine else ('panako.monitor.request', 'panako-workers')
    redis = Redis(settings)

    def pending():
        summary = redis.cmd('XPENDING', stream, group)
        return sum(int(n) for name, n in (summary[3] or []) if name.startswith(c['Config']['Hostname'] + '-'))

    paused = False
    try:
        if refine:
            response = json.loads(run(['python3', '/home/bekzat/latency-fleet-20261012/drain-control.py', c['Id'], 'drain']))
            assert response['registered'] == 1 and not response['running_flag']
        deadline = time.monotonic() + 1200
        while time.monotonic() < deadline:
            if pending():
                time.sleep(.25 if not refine else 2)
                continue
            if not refine:
                run(['docker', 'pause', c['Id']])
                paused = True
                time.sleep(int(settings.get('REDIS_BLOCK_MS', '5000')) / 1000 + 1)
                if pending():
                    run(['docker', 'unpause', c['Id']])
                    paused = False
                    continue
            assert pending() == 0
            run(['docker', 'update', '--restart=no', c['Id']])
            if refine:
                run(['docker', 'stop', '--timeout', '30', c['Id']])
            else:
                run(['docker', 'kill', '--signal=SIGKILL', c['Id']])
                paused = False
            assert not inspect(c['Id'])['State']['Running'] and pending() == 0
            receipt = {'id': c['Id'], 'name': c['Name'], 'at': stamp(), 'pending': 0, 'retired': True}
            save(c['Id'] + '-retired.json', receipt)
            return receipt
        raise RuntimeError('Drain deadline; current task left intact')
    finally:
        if paused:
            run(['docker', 'unpause', c['Id']])


try:
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(retired)) as pool:
        receipts = list(pool.map(drain, retired))
    for filename, entry in backup['files'].items():
        file = pathlib.Path(filename)
        assert hashlib.sha256(file.read_bytes()).hexdigest() == entry['sha256']
        doc = json.loads(entry['content'])
        doc['services'][entry['service']]['deploy']['replicas'] = 0
        tmp = file.with_suffix('.rebalance.tmp')
        tmp.write_text(json.dumps(doc, indent=2))
        tmp.replace(file)
    mem = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    doc = json.loads((ROOT / 'compose-private.json').read_text())
    additional = sum(s['mem_limit'] * s['deploy']['replicas'] for s in doc['services'].values())
    assert sum(c['HostConfig']['Memory'] for c in running()) + additional < mem['MemTotal'] * 1024 - 2 * 1024**3
    state.update(phase='starting', retired=receipts)
    save('status.json', state)
    run(compose + ['up', '-d', '--no-build', '--pull', 'never'], timeout=180)
    new = [c for c in running() if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == PROJECT]
    assert len(new) == len(retired)
    for c in new:
        role = c['Config']['Labels']['com.docker.compose.service']
        s = doc['services'][role]
        assert env(c) == expected_env[role] and c['Image'] == s['image']
        assert c['HostConfig']['Memory'] == s['mem_limit'] and c['HostConfig']['NanoCpus'] == int(s['cpus'] * 1e9)
        assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == JAR
        ip = next(x['IPAddress'] for x in c['NetworkSettings']['Networks'].values() if x['IPAddress'])
        for _ in range(60):
            try:
                with urllib.request.urlopen('http://' + ip + ':8080/api/v1/health', timeout=3) as response:
                    assert json.load(response)['status'] == 'ok'
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('New worker health check failed')
    assert all(inspect(name)['Id'] == cid and inspect(cid)['State']['Running'] for name, cid in backup['others'].items())
    state.update(phase='healthy', finished_at=stamp(), others_unchanged=True,
                 new=[{'name': c['Name'], 'id': c['Id'], 'redis': env(c)['REDIS_HOST']} for c in new])
    save('status.json', state)
    print(json.dumps(state))
except BaseException as ex:
    state.update(phase='failed', error=type(ex).__name__)
    save('status.json', state)
    raise
