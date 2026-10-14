"""Move three drained Main refine slots to the explicitly requested Redis 10.0.0.4."""
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

p = pathlib.Path('/home/bekzat/isolated-refine-five-20261014')
project = 'panako-isolated-refine-five-20261014'
jar = '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
old_file = pathlib.Path('/home/bekzat/rebalance-20261014/compose-private.json')
names = ['panako-refine-rebalance-20261014-refine-main-' + str(n) for n in (2, 3, 4)]
action = sys.argv[1]
assert action in ('prepare', 'apply')


def save(name, value):
    f = p / name
    tmp = f.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, indent=2))
    tmp.replace(f)


def run(args):
    r = subprocess.run(args, capture_output=True, text=True, timeout=120)
    if r.returncode:
        save('command-error-private.json', {'stdout': r.stdout, 'stderr': r.stderr})
        raise RuntimeError('Command failed; protected details saved')
    return r.stdout.strip()


def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]


def running():
    ids = run(['docker', 'ps', '-q']).split()
    return json.loads(run(['docker', 'inspect', *ids])) if ids else []


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


compose = ['docker', 'compose', '-p', project, '-f', str(p / 'compose-private.json')]
if action == 'prepare':
    assert not (p / 'plan.json').exists()
    source = json.loads((p / 'source-private.json').read_text())
    e = env(source)
    assert e['REDIS_HOST'] == '10.0.0.4' and e['PANAKO_WORKER_MODE'] == 'refine'
    assert e['CLICKHOUSE_HOST'] == '10.0.1.2' and e['REDIS_WORKER_THREADS'] == '1'
    assert e['MONITOR_PARALLEL_WINDOWS'] == '2' and '3584m' in e.get('JAVA_TOOL_OPTIONS', '') + e.get('JDK_JAVA_OPTIONS', '')
    assert Redis(e).cmd('PING') == 'PONG'
    old = [inspect(n) for n in names]
    before = running()
    assert len(before) == 4, 'Recheck changed host capacity'
    assert all(c['State']['Running'] and not c['State']['Paused'] for c in old)
    for c in old:
        assert env(c)['PANAKO_WORKER_MODE'] == 'refine' and env(c)['REDIS_HOST'] == '10.0.0.2'
        assert env(c)['REDIS_WORKER_THREADS'] == '1'
        assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == jar
    assert len({c['Image'] for c in old}) == 1
    expected = json.loads((p / 'runtime-hashes.json').read_text())
    output = run(['docker', 'exec', old[0]['Id'], 'sha256sum', *expected])
    actual = {line.split(None, 1)[1]: line.split()[0] for line in output.splitlines()}
    assert actual == expected
    agent = pathlib.Path('/home/bekzat/latency-fleet-20261012/drain-agent/drain-agent.jar')
    assert hashlib.sha256(agent.read_bytes()).hexdigest() == '7fb564fca397860a5d6decfcbfe17cda9f4a403d03e48513ed927fc1976ff4a9'
    run(['docker', 'image', 'inspect', 'eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda'])
    raw = old_file.read_text()
    doc = json.loads(raw)
    assert set(doc['services']) == {'refine-main'} and doc['services']['refine-main']['deploy']['replicas'] == 4
    hc = source['HostConfig']
    assert hc['Memory'] == 4608 * 1024**2 and hc['NanoCpus'] == 3000000000
    others = {c['Name']: c['Id'] for c in before if c['Id'] not in {c['Id'] for c in old}}
    assert len(others) == 1 and next(iter(others)).endswith('refine-main-1')
    mem = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    reserved = sum(c['HostConfig']['Memory'] for c in before)
    assert reserved < mem['MemTotal'] * 1024 - 2 * 1024**3
    service = {'image': old[0]['Image'], 'entrypoint': source['Config']['Entrypoint'], 'command': source['Config']['Cmd'],
        'environment': {k: v.replace('$', '$$') for k, v in e.items()}, 'restart': 'unless-stopped',
        'mem_limit': hc['Memory'], 'memswap_limit': hc['MemorySwap'], 'cpus': 3,
        'deploy': {'replicas': 3}, 'logging': {'driver': hc['LogConfig']['Type'], 'options': hc['LogConfig']['Config']}}
    save('before-private.json', {'containers': old, 'others': others, 'compose_content': raw,
        'compose_sha256': hashlib.sha256(raw.encode()).hexdigest()})
    save('compose-private.json', {'services': {'refine-isolated': service}})
    rendered = json.loads(run(compose + ['config', '--format', 'json']))
    assert rendered['services']['refine-isolated']['environment'] == e
    plan = {'at': stamp(), 'host': '46.224.159.198', 'add_isolated_refine': 3, 'remaining_main_refine_on_host': 1,
            'redis': '10.0.0.4', 'clickhouse': '10.0.1.2', 'reserved_gib': reserved / 1024**3, 'jar_sha256': jar}
    save('plan.json', plan)
    print(json.dumps(plan))
    sys.exit(0)

assert (p / 'plan.json').exists() and not (p / 'status.json').exists()
backup = json.loads((p / 'before-private.json').read_text())
old = backup['containers']
assert hashlib.sha256(old_file.read_bytes()).hexdigest() == backup['compose_sha256']
assert all(inspect(c['Id'])['State']['Running'] and env(inspect(c['Id'])) == env(c) for c in old)
state = {'started_at': stamp(), 'phase': 'draining'}
save('status.json', state)


def retire(c):
    redis = Redis(env(c))
    result = json.loads(run(['python3', '/home/bekzat/latency-fleet-20261012/drain-control.py', c['Id'], 'drain']))
    assert result['registered'] == 1 and not result['running_flag']

    def pending():
        summary = redis.cmd('XPENDING', 'panako.monitor.refine.request', 'panako-worker-refine')
        return sum(int(n) for name, n in (summary[3] or []) if name.startswith(c['Config']['Hostname'] + '-'))

    deadline = time.monotonic() + 1200
    while pending():
        if time.monotonic() > deadline:
            raise RuntimeError('Drain deadline; active task retained')
        time.sleep(2)
    run(['docker', 'update', '--restart=no', c['Id']])
    run(['docker', 'stop', '--timeout', '30', c['Id']])
    assert not inspect(c['Id'])['State']['Running'] and pending() == 0
    receipt = {'id': c['Id'], 'name': c['Name'], 'at': stamp(), 'pending': 0}
    save(c['Id'] + '-retired.json', receipt)
    return receipt


try:
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        receipts = list(pool.map(retire, old))
    assert hashlib.sha256(old_file.read_bytes()).hexdigest() == backup['compose_sha256']
    doc = json.loads(backup['compose_content'])
    doc['services']['refine-main']['deploy']['replicas'] = 1
    tmp = old_file.with_suffix('.isolated-five.tmp')
    tmp.write_text(json.dumps(doc, indent=2))
    tmp.replace(old_file)
    mem = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    assert sum(c['HostConfig']['Memory'] for c in running()) + 3 * 4608 * 1024**2 < mem['MemTotal'] * 1024 - 2 * 1024**3
    state.update(phase='starting', retired=receipts)
    save('status.json', state)
    run(compose + ['up', '-d', '--no-build', '--pull', 'never'])
    new = [c for c in running() if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == project]
    assert len(new) == 3
    source_env = env(json.loads((p / 'source-private.json').read_text()))
    for c in new:
        assert env(c) == source_env and c['Image'] == old[0]['Image']
        assert c['HostConfig']['Memory'] == 4608 * 1024**2 and c['HostConfig']['NanoCpus'] == 3000000000
        assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == jar
        ip = next(n['IPAddress'] for n in c['NetworkSettings']['Networks'].values() if n['IPAddress'])
        for _ in range(60):
            try:
                with urllib.request.urlopen('http://' + ip + ':8080/api/v1/health', timeout=3) as r:
                    assert json.load(r)['status'] == 'ok'
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('New refine health check failed')
    assert all(inspect(name)['Id'] == cid and inspect(cid)['State']['Running'] for name, cid in backup['others'].items())
    state.update(phase='healthy', finished_at=stamp(), others_unchanged=True,
                 new=[{'name': c['Name'], 'id': c['Id']} for c in new])
    save('status.json', state)
    print(json.dumps(state))
except BaseException as error:
    state.update(phase='failed', error=type(error).__name__)
    save('status.json', state)
    for c in old:
        if inspect(c['Id'])['State']['Running']:
            subprocess.run(['python3', '/home/bekzat/latency-fleet-20261012/drain-control.py', c['Id'], 'resume'], capture_output=True)
    raise
