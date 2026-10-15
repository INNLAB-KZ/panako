"""Bounded host evacuation, preserving deployed code and Redis task ownership.

Run on the relevant host. Private inputs/state stay under ROOT (0700).
No request/result stream writes, claims, ACKs or consumer-group resets.
"""
import datetime
import hashlib
import http.client
import json
import os
import pathlib
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.parse

os.umask(0o077)
ROOT = pathlib.Path('/home/bekzat/host-isolation-20261013')
ROOT.mkdir(mode=0o700, exist_ok=True)
sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env

PROJECT = 'panako-stage1-evac-20261013'
JAR = '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
FILES = ['/app/panako.jar', '/opt/java/openjdk/bin/java',
         '/opt/java/openjdk/lib/server/libjvm.so', '/usr/bin/ffmpeg',
         '/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2', '/lib/x86_64-linux-gnu/libc.so.6']


def run(args):
    p = subprocess.run(args, capture_output=True, text=True)
    if p.returncode:
        (ROOT / 'command-error-private.log').write_text(p.stdout + p.stderr)
        raise RuntimeError('Command failed; details in protected log')
    return p.stdout.strip()


def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]


def all_running():
    ids = run(['docker', 'ps', '-q']).split()
    return json.loads(run(['docker', 'inspect', *ids])) if ids else []


def save(name, data):
    tmp = ROOT / (name + '.tmp')
    tmp.write_text(json.dumps(data, indent=2))
    tmp.replace(ROOT / name)


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def hashes(c):
    return {line.split(None, 1)[1].strip(): line.split()[0]
            for line in run(['docker', 'exec', c['Id'], 'sha256sum', *FILES]).splitlines()}


def pending(c):
    r = Redis(env(c))
    p = r.cmd('XPENDING', 'panako.monitor.request', 'panako-workers')
    return sum(int(n) for name, n in (p[3] or [])
               if name.startswith(c['Config']['Hostname'] + '-'))


def pause(cid):
    class Engine(http.client.HTTPConnection):
        def connect(self):
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.settimeout(self.timeout)
            self.sock.connect('/var/run/docker.sock')
    conn = Engine('localhost', timeout=10)
    try:
        conn.request('POST', '/containers/' + cid + '/pause', body=b'')
        response = conn.getresponse()
        response.read()
        assert response.status == 204
    finally:
        conn.close()


def selected(c):
    return (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == PROJECT


def health(c):
    ip = next(n['IPAddress'] for n in c['NetworkSettings']['Networks'].values() if n['IPAddress'])
    try:
        with urllib.request.urlopen('http://' + ip + ':8080/api/v1/health', timeout=3) as response:
            return json.load(response).get('status') == 'ok'
    except Exception:
        return False


def compose():
    return ['docker', 'compose', '-p', PROJECT, '-f', str(ROOT / 'compose-private.json')]


def target_settings(source, autotune):
    settings = env(source)
    if autotune:
        url = settings['OLAF_CLICKHOUSE_URL']
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url.removeprefix('jdbc:')).query)
        assert not {'socket_rcvbuf', 'socket_sndbuf'} & query.keys()
        assert '#' not in url
        settings['OLAF_CLICKHOUSE_URL'] = url + ('&' if '?' in url else '?') + 'socket_rcvbuf=0&socket_sndbuf=0'
    return settings


action = sys.argv[1]
if action == 'source-prepare':
    cs = [c for c in all_running() if c['Name'].startswith('/project-panako-api-')
          and env(c).get('PANAKO_WORKER_MODE') == 'stage1']
    assert len(cs) == 6 and all(not c['Mounts'] for c in cs)
    assert len({c['Image'] for c in cs}) == 1
    assert not (ROOT / 'source-private.json').exists()
    fingerprints = hashes(cs[0])
    assert fingerprints['/app/panako.jar'] == JAR
    assert all(env(c) == env(cs[0]) for c in cs)
    source = {'at': stamp(), 'containers': cs, 'hashes': fingerprints}
    save('source-private.json', source)
    print(json.dumps({'saved': True, 'workers': [c['Name'] for c in cs], 'hashes': fingerprints}))

elif action == 'target-prepare':
    count = int(sys.argv[2])
    assert count in (1, 2)
    source = json.loads((ROOT / 'source-private.json').read_text())
    original = source['containers'][0]
    cs = all_running()
    assert not any(selected(c) for c in cs)
    existing = [c for c in cs if env(c).get('PANAKO_WORKER_MODE') in ('stage1', 'refine')]
    assert existing and not any(c['RestartCount'] or c['State']['OOMKilled'] for c in existing)
    image = json.loads(run(['docker', 'image', 'inspect', original['Image']]))[0]
    assert image['Id'] == original['Image']
    file_hashes = {line.split(None, 1)[1].strip(): line.split()[0] for line in run([
        'docker', 'run', '--rm', '--network=none', '--read-only', '--memory=64m', '--cpus=.25',
        '--entrypoint', 'sha256sum', image['Id'], *FILES]).splitlines()}
    assert file_hashes == source['hashes'], 'Runtime/application files differ'
    mem = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    reserved = sum(c['HostConfig']['Memory'] for c in cs)
    assert all(c['HostConfig']['Memory'] > 0 for c in existing)
    assert reserved + count * original['HostConfig']['Memory'] < mem['MemTotal'] * 1024 - 2 * 1024**3
    assert mem['MemAvailable'] * 1024 > count * original['HostConfig']['Memory'] + 2 * 1024**3
    autotune = len(sys.argv) == 4 and sys.argv[3] == 'autotune'
    settings = target_settings(original, autotune)
    assert settings['REDIS_HOST'] == '10.0.0.2' and settings['CLICKHOUSE_HOST'] == '10.0.1.2'
    assert settings['MONITOR_PARALLEL_WINDOWS'] == '2' and settings['REDIS_WORKER_THREADS'] == '1'
    hc = original['HostConfig']
    assert hc['NanoCpus'] == 0 and not hc['PortBindings'] and not original['Mounts']
    service = {'image': image['Id'], 'entrypoint': original['Config']['Entrypoint'],
               'command': original['Config']['Cmd'],
               'environment': {k: v.replace('$', '$$') for k, v in settings.items()},
               'restart': 'unless-stopped', 'mem_limit': hc['Memory'],
               'memswap_limit': hc['MemorySwap'], 'deploy': {'replicas': count},
               'logging': {'driver': hc['LogConfig']['Type'], 'options': hc['LogConfig']['Config']}}
    save('compose-private.json', {'services': {'stage1': service}})
    rendered = json.loads(run(compose() + ['config', '--format', 'json']))
    assert rendered['services']['stage1']['environment'] == settings
    plan = {'at': stamp(), 'max_count': count, 'image': image['Id'],
            'transport_autotune': autotune,
            'original_running': {c['Name']: c['Id'] for c in cs},
            'expected_hashes': source['hashes'], 'memory_reserved_after_gib':
                (reserved + count * hc['Memory']) / 1024**3}
    save('target-plan.json', plan)
    print(json.dumps(plan))

elif action in ('retire', 'target-retire'):
    name = sys.argv[2]
    source = json.loads((ROOT / 'source-private.json').read_text())
    c = inspect(name)
    old = (next(c for c in source['containers'] if c['Name'].lstrip('/') == name)
           if action == 'retire' else c)
    if action == 'target-retire':
        assert selected(c) and c['Image'] == source['containers'][0]['Image']
    assert c['Id'] == old['Id'] and c['State']['Running'] and not c['State']['Paused']
    assert env(c)['REDIS_WORKER_THREADS'] == '1'
    paused = False
    policy_changed = False
    state = {'name': name, 'id': c['Id'], 'started_at': stamp(), 'phase': 'waiting_for_idle'}
    save(name + '-retirement.json', state)
    deadline = time.monotonic() + 1200
    try:
        while time.monotonic() < deadline:
            if pending(c):
                time.sleep(.2)
                continue
            pause(c['Id'])
            paused = True
            time.sleep(int(env(c).get('REDIS_BLOCK_MS', '5000')) / 1000 + 1)
            if pending(c):
                run(['docker', 'unpause', c['Id']])
                paused = False
                continue
            assert inspect(c['Id'])['State']['Paused'] and pending(c) == 0
            run(['docker', 'update', '--restart=no', c['Id']])
            policy_changed = True
            # Frozen, no active task and no in-flight blocked Redis read remains.
            # SIGKILL prevents a fresh read between unpause and graceful shutdown.
            run(['docker', 'kill', '--signal=SIGKILL', c['Id']])
            paused = False
            now = inspect(c['Id'])
            assert not now['State']['Running'] and pending(c) == 0
            state.update(phase='retired', finished_at=stamp(), pending_after=0,
                         intentional_idle_stop=True, restart_policy=now['HostConfig']['RestartPolicy'])
            save(name + '-retirement.json', state)
            print(json.dumps(state))
            break
        else:
            raise RuntimeError('Idle deadline; active task left intact')
    except BaseException:
        if paused:
            subprocess.run(['docker', 'unpause', c['Id']], capture_output=True)
        if policy_changed and inspect(c['Id'])['State']['Running']:
            run(['docker', 'update', '--restart=unless-stopped', c['Id']])
        raise

elif action == 'target-start':
    count = int(sys.argv[2])
    plan = json.loads((ROOT / 'target-plan.json').read_text())
    assert 1 <= count <= plan['max_count']
    current = [c for c in all_running() if selected(c)]
    assert len(current) == count - 1, 'Only add one replacement at a time'
    source = json.loads((ROOT / 'source-private.json').read_text())['containers'][0]
    memory = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    reserved = sum(c['HostConfig']['Memory'] for c in all_running())
    assert reserved + source['HostConfig']['Memory'] < memory['MemTotal'] * 1024 - 2 * 1024**3
    assert memory['MemAvailable'] * 1024 > source['HostConfig']['Memory'] + 2 * 1024**3
    run(compose() + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--scale', 'stage1=' + str(count), 'stage1'])
    new = [c for c in all_running() if selected(c)]
    assert len(new) == count
    for c in new:
        assert c['Image'] == plan['image'] and env(c) == target_settings(source, plan['transport_autotune'])
        assert hashes(c) == plan['expected_hashes']
        for key in ('Memory', 'MemorySwap', 'NanoCpus', 'RestartPolicy', 'PortBindings'):
            assert c['HostConfig'][key] == source['HostConfig'][key], key
        for _ in range(60):
            if health(c):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Replacement health check failed')
    assert all(inspect(n)['Id'] == cid and inspect(n)['State']['Running']
               for n, cid in plan['original_running'].items())
    state = {'at': stamp(), 'phase': 'healthy', 'count': count,
             'containers': [{'name': c['Name'], 'id': c['Id']} for c in new], 'existing_unchanged': True}
    save('target-status.json', state)
    print(json.dumps(state))

elif action == 'source-finalize':
    source = json.loads((ROOT / 'source-private.json').read_text())
    assert all(not inspect(c['Id'])['State']['Running'] and pending(c) == 0 for c in source['containers'])
    path = pathlib.Path(source['containers'][0]['Config']['Labels']['com.docker.compose.project.config_files'].split(',')[-1])
    assert path.name == 'docker-compose.latency-20261012-panako-api.json'
    previous = path.read_text()
    config = json.loads(previous)
    assert config['services']['panako-api']['deploy']['replicas'] == 6
    (ROOT / 'source-compose-override-before.json').write_text(previous)
    config['services']['panako-api']['deploy']['replicas'] = 0
    temporary = path.with_suffix('.isolation.tmp')
    temporary.write_text(json.dumps(config, indent=2))
    temporary.replace(path)
    save('source-final.json', {'at': stamp(), 'phase': 'isolated', 'stopped_count': 6,
                             'pending': 0, 'compose_replicas': 0, 'containers_retained': True})
    print((ROOT / 'source-final.json').read_text())
else:
    raise ValueError('Unknown action')
