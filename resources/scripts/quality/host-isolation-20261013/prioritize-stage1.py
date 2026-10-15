"""Temporarily exchange four refine slots for Stage-1, without interrupting tasks."""
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
ROOT = pathlib.Path('/home/bekzat/host-isolation-20261013')
sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env

HOSTS = {
    '46.224.159.198': ['refine-move-20260927-refine-from-main-1',
                     'refine-e-move-20261006-refine-from-main-1',
                     'refine-c-move-20261006-refine-from-main-1'],
    '91.99.52.184': ['refine-d-move-20261006-refine-from-main-1'],
}
host = sys.argv[1]
assert host in HOSTS and len(sys.argv) == 2
PROJECT = 'panako-stage1-priority-20261013'
JAR = '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
p = ROOT / 'priority'
p.mkdir(mode=0o700, exist_ok=True)
assert not (p / 'status.json').exists(), 'Operation already attempted; inspect its receipt before proceeding'


def run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode:
        (p / 'command-error-private.log').write_text(r.stdout + r.stderr)
        raise RuntimeError('Command failed; protected log saved')
    return r.stdout.strip()


def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]


def running():
    ids = run(['docker', 'ps', '-q']).split()
    return json.loads(run(['docker', 'inspect', *ids])) if ids else []


def save(name, value):
    tmp = p / (name + '.tmp')
    tmp.write_text(json.dumps(value, indent=2))
    tmp.replace(p / name)


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


source = json.loads((ROOT / 'source-private.json').read_text())['containers'][0]
all_before = running()
old = [inspect(name) for name in HOSTS[host]]
assert all(c['State']['Running'] and not c['State']['Paused'] for c in old)
assert not any((c['Config'].get('Labels') or {}).get('com.docker.compose.project') == PROJECT for c in all_before)
assert json.loads(run(['docker', 'image', 'inspect', source['Image']]))[0]['Id'] == source['Image']
others = {c['Name']: c['Id'] for c in all_before if c['Id'] not in {o['Id'] for o in old}}
memory = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
reserved_after = sum(c['HostConfig']['Memory'] for c in all_before) - sum(c['HostConfig']['Memory'] for c in old) + len(old) * 4608 * 1024**2
assert reserved_after < memory['MemTotal'] * 1024 - 2 * 1024**3
backups = []
for c in old:
    settings = env(c)
    assert settings['PANAKO_WORKER_MODE'] == 'refine' and settings['REDIS_HOST'] == '10.0.0.2'
    assert settings['CLICKHOUSE_HOST'] == '10.0.1.2'
    assert settings['REDIS_WORKER_THREADS'] == '1' and settings['MONITOR_PARALLEL_WINDOWS'] == '2'
    assert c['HostConfig']['Memory'] == 4608 * 1024**2
    assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == JAR
    labels = c['Config']['Labels']
    service = labels['com.docker.compose.service']
    file = pathlib.Path(labels['com.docker.compose.project.config_files'].split(',')[-1])
    original = file.read_text()
    config = json.loads(original)
    assert set(config['services']) == {service} and config['services'][service]['deploy']['replicas'] == 1
    backups.append({'file': str(file), 'service': service, 'content': original, 'sha256': hashlib.sha256(original.encode()).hexdigest()})
save('before-private.json', {'containers': old, 'others': others, 'overrides': backups})

agent = pathlib.Path('/home/bekzat/latency-fleet-20261012/drain-agent/drain-agent.jar')
assert hashlib.sha256(agent.read_bytes()).hexdigest() == '7fb564fca397860a5d6decfcbfe17cda9f4a403d03e48513ed927fc1976ff4a9'
run(['docker', 'image', 'inspect', 'eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda'])
settings = env(source)
url = settings['OLAF_CLICKHOUSE_URL']
assert 'socket_rcvbuf=' not in url and 'socket_sndbuf=' not in url and '#' not in url
settings['OLAF_CLICKHOUSE_URL'] = url + ('&' if '?' in url else '?') + 'socket_rcvbuf=0&socket_sndbuf=0'
service = {'image': source['Image'], 'entrypoint': source['Config']['Entrypoint'],
           'command': source['Config']['Cmd'], 'environment': {k: v.replace('$', '$$') for k, v in settings.items()},
           'restart': 'unless-stopped', 'mem_limit': 4608 * 1024**2,
           'memswap_limit': source['HostConfig']['MemorySwap'], 'deploy': {'replicas': len(old)}}
save('compose-private.json', {'services': {'stage1': service}})
compose = ['docker', 'compose', '-p', PROJECT, '-f', str(p / 'compose-private.json')]
assert json.loads(run(compose + ['config', '--format', 'json']))['services']['stage1']['environment'] == settings
state = {'at': stamp(), 'phase': 'draining', 'host': host, 'count': len(old), 'old_names': HOSTS[host],
         'reserved_after_gib': reserved_after / 1024**3, 'stopped': [], 'new': []}
save('status.json', state)
r = Redis(env(old[0]))


def pending(c):
    summary = r.cmd('XPENDING', 'panako.monitor.refine.request', 'panako-worker-refine')
    return sum(int(n) for name, n in (summary[3] or []) if name.startswith(c['Config']['Hostname'] + '-'))


try:
    for c in old:
        status = json.loads(run(['python3', '/home/bekzat/latency-fleet-20261012/drain-control.py', c['Id'], 'drain']))
        assert status['registered'] == 1 and not status['running_flag']
    deadline = time.monotonic() + 1800
    while True:
        counts = {c['Name']: pending(c) for c in old}
        state['pending'] = counts
        save('status.json', state)
        if not any(counts.values()):
            break
        if time.monotonic() > deadline:
            raise RuntimeError('Cooperative drain deadline; active tasks retained')
        time.sleep(5)
    for c, backup in zip(old, backups):
        assert pending(c) == 0
        file = pathlib.Path(backup['file'])
        assert hashlib.sha256(file.read_bytes()).hexdigest() == backup['sha256']
        run(['docker', 'update', '--restart=no', c['Id']])
        run(['docker', 'stop', '--timeout', '30', c['Id']])
        assert not inspect(c['Id'])['State']['Running'] and pending(c) == 0
        config = json.loads(backup['content'])
        config['services'][backup['service']]['deploy']['replicas'] = 0
        temporary = file.with_suffix('.priority.tmp')
        temporary.write_text(json.dumps(config, indent=2))
        temporary.replace(file)
        state['stopped'].append(c['Name'])
        save('status.json', state)
    assert sum(c['HostConfig']['Memory'] for c in running()) + len(old) * 4608 * 1024**2 < memory['MemTotal'] * 1024 - 2 * 1024**3
    state['phase'] = 'starting_stage1'
    save('status.json', state)
    run(compose + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'stage1'])
    new = [c for c in running() if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == PROJECT]
    assert len(new) == len(old)
    for c in new:
        assert c['Image'] == source['Image'] and env(c) == settings
        assert c['HostConfig']['Memory'] == 4608 * 1024**2 and c['HostConfig']['NanoCpus'] == 0
        assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == JAR
        ip = next(n['IPAddress'] for n in c['NetworkSettings']['Networks'].values() if n['IPAddress'])
        for _ in range(60):
            try:
                with urllib.request.urlopen('http://' + ip + ':8080/api/v1/health', timeout=3) as response:
                    assert json.load(response)['status'] == 'ok'
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('New Stage-1 health check failed')
    assert all(inspect(name)['Id'] == cid and inspect(name)['State']['Running'] for name, cid in others.items())
    state.update(phase='healthy', finished_at=stamp(), others_unchanged=True,
                 new=[{'id': c['Id'], 'name': c['Name']} for c in new], old_pending=0)
    save('status.json', state)
    print(json.dumps(state))
except BaseException as error:
    state.update(phase='failed', error=type(error).__name__)
    save('status.json', state)
    # Restore consumption if no old service was stopped and no replacement exists.
    if not state['stopped']:
        for c in old:
            subprocess.run(['python3', '/home/bekzat/latency-fleet-20261012/drain-control.py', c['Id'], 'resume'], capture_output=True)
    raise
