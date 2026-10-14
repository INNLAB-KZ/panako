"""Prepare/deploy/rollback one existing Compose worker service, preserving settings."""
import copy, datetime, hashlib, http.client, json, os, pathlib, socket, subprocess, sys, time, urllib.request, zipfile
from redis_state import Redis, env, pairs

os.umask(0o077)
ROOT = pathlib.Path('/home/bekzat/latency-fleet-20261012')
OLD = '60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
NEW = '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
action, project, service = sys.argv[1:]
assert action in ('prepare', 'deploy', 'rollback')
assert all(x and all(c.isalnum() or c in '-_' for c in x) for x in (project, service))
p = ROOT / (project + '__' + service)
p.mkdir(mode=0o700, parents=True, exist_ok=True)

def run(args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        (p / 'command-error.log').write_text(result.stdout + result.stderr)
        raise RuntimeError('Command failed; protected diagnostic log saved')
    return result.stdout.strip()

def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]

def pause_container(container_id):
    """Freeze through the local Engine socket without Docker CLI startup delay."""
    assert len(container_id) == 64 and all(c in '0123456789abcdef' for c in container_id)
    class EngineConnection(http.client.HTTPConnection):
        def connect(self):
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.settimeout(self.timeout)
            self.sock.connect('/var/run/docker.sock')
    connection = EngineConnection('localhost', timeout=10)
    try:
        connection.request('POST', '/containers/' + container_id + '/pause', body=b'')
        response = connection.getresponse()
        response.read()
        if response.status != 204:
            raise RuntimeError('Docker pause returned HTTP ' + str(response.status))
    finally:
        connection.close()

def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def save(path, value):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, indent=2))
    temp.replace(path)

def containers():
    ids = run(['docker', 'ps', '-q']).split()
    return json.loads(run(['docker', 'inspect', *ids])) if ids else []

def selected(c):
    labels = c['Config'].get('Labels') or {}
    return labels.get('com.docker.compose.project') == project and labels.get('com.docker.compose.service') == service

def healthy(c):
    port = next(int(a.split('=', 1)[1]) for a in c['Config']['Cmd'] if a.startswith('SERVER_PORT='))
    host = '127.0.0.1' if c['HostConfig']['NetworkMode'] == 'host' else next(
        n['IPAddress'] for n in c['NetworkSettings']['Networks'].values() if n['IPAddress'])
    try:
        with urllib.request.urlopen('http://' + host + ':' + str(port) + '/api/v1/health', timeout=3) as r:
            return json.load(r).get('status') == 'ok'
    except Exception:
        return False

if action == 'prepare':
    if (p / 'plan.json').exists():
        existing = json.loads((p / 'plan.json').read_text())
        assert all(hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest() == sha for f, sha in existing['files'].items())
        assert hashlib.sha256((ROOT / 'panako.jar').read_bytes()).hexdigest() == NEW
        # Each service needs its own tag. Repeated builds against the same parent
        # must not replace the only reference to an earlier prepared image.
        tag = 'ozen-panako:latency-20261012-' + project + '-' + service
        cached = subprocess.run(['docker', 'image', 'inspect', existing['image']], capture_output=True)
        if cached.returncode:
            base_tag = 'ozen-panako:latency-base-' + existing['old_image'].split(':')[1][:12]
            run(['docker', 'tag', existing['old_image'], base_tag])
            (ROOT / 'Dockerfile').write_text('ARG BASE_IMAGE\nFROM ${BASE_IMAGE}\nCOPY panako.jar /app/panako.jar\n')
            (ROOT / '.dockerignore').write_text('*\n!Dockerfile\n!panako.jar\n')
            run(['docker', 'build', '--pull=false', '--network=none', '--build-arg', 'BASE_IMAGE=' + base_tag,
                 '-t', tag, str(ROOT)])
        else:
            run(['docker', 'tag', existing['image'], tag])
        image = inspect(tag)
        parent = inspect(existing['old_image'])
        assert image['Config']['Env'] == parent['Config']['Env']
        assert image['RootFS']['Layers'][:len(parent['RootFS']['Layers'])] == parent['RootFS']['Layers']
        assert run(['docker', 'run', '--rm', '--network', 'none', '--memory', '64m', '--cpus', '0.25',
                    '--entrypoint', 'sha256sum', image['Id'], '/app/panako.jar']).split()[0] == NEW
        existing['image'] = image['Id']
        existing['image_tag'] = tag
        proposal = json.loads((p / 'proposal.json').read_text())
        proposal['services'][service]['image'] = image['Id']
        save(p / 'proposal.json', proposal)
        save(p / 'plan.json', existing)
        print(json.dumps(existing))
        raise SystemExit(0)
    all_before = containers()
    old = [c for c in all_before if selected(c)]
    assert old and len({c['Image'] for c in old}) == 1
    settings = env(old[0])
    assert settings.get('KAFKA_MODE', '').upper() != 'STORE'
    assert settings['PANAKO_WORKER_MODE'] in ('stage1', 'refine') and settings['REDIS_HOST'] == '10.0.0.2'
    assert settings['MONITOR_PARALLEL_WINDOWS'] == '2' and settings['REDIS_WORKER_THREADS'] == '1'
    for c in old:
        assert c['State']['Running'] and not c['State']['Paused'] and not c['RestartCount']
        assert not c['Mounts'] and env(c) == settings
        assert c['HostConfig']['Memory'] == 4608 * 1024 ** 2
        assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == OLD
    if not (ROOT / 'panako.jar').exists():
        run(['docker', 'cp', old[0]['Id'] + ':/app/panako.jar', str(ROOT / 'baseline.jar')])
        assert hashlib.sha256((ROOT / 'baseline.jar').read_bytes()).hexdigest() == OLD
        with zipfile.ZipFile(ROOT / 'baseline.jar') as source, zipfile.ZipFile(ROOT / 'overlay.zip') as patch, zipfile.ZipFile(ROOT / 'panako.jar', 'w') as target:
            names = set(patch.namelist())
            assert names == {'be/panako/strategy/olaf/OlafStrategy.class', 'be/panako/strategy/olaf/OlafMatchBuckets.class'}
            for entry in source.infolist():
                if entry.filename not in names:
                    target.writestr(entry, source.read(entry.filename))
            for entry in patch.infolist():
                target.writestr(entry, patch.read(entry.filename))
    assert hashlib.sha256((ROOT / 'panako.jar').read_bytes()).hexdigest() == NEW
    labels = old[0]['Config']['Labels']
    files = labels['com.docker.compose.project.config_files'].split(',')
    workdir = labels.get('com.docker.compose.project.working_dir', str(pathlib.Path(files[0]).parent))
    base = ['docker', 'compose', '-p', project, '--project-directory', workdir]
    for f in files:
        base += ['-f', f]
    base_tag = 'ozen-panako:latency-base-' + old[0]['Image'].split(':')[1][:12]
    tag = 'ozen-panako:latency-20261012-' + project + '-' + service
    run(['docker', 'tag', old[0]['Image'], base_tag])
    assert inspect(base_tag)['Id'] == old[0]['Image']
    dockerfile = ROOT / 'Dockerfile'
    dockerfile.write_text('ARG BASE_IMAGE\nFROM ${BASE_IMAGE}\nCOPY panako.jar /app/panako.jar\n')
    (ROOT / '.dockerignore').write_text('*\n!Dockerfile\n!panako.jar\n')
    run(['docker', 'build', '--pull=false', '--network=none', '--build-arg', 'BASE_IMAGE=' + base_tag,
         '-t', tag, str(ROOT)])
    image = inspect(tag)
    assert image['Config']['Env'] == inspect(old[0]['Image'])['Config']['Env']
    override = pathlib.Path(files[0]).parent / ('docker-compose.latency-20261012-' + service + '.json')
    assert not override.exists()
    proposal = {'services': {service: {'image': image['Id'], 'deploy': {'replicas': len(old)}}}}
    (p / 'proposal.json').write_text(json.dumps(proposal, indent=2))
    a = json.loads(run(base + ['config', '--format', 'json']))
    b = json.loads(run(base + ['-f', str(p / 'proposal.json'), 'config', '--format', 'json']))
    expected = copy.deepcopy(a)
    expected['services'][service]['image'] = image['Id']
    expected['services'][service].setdefault('deploy', {})['replicas'] = len(old)
    # Compose versions can add empty placement/resources maps when deploy is introduced.
    def prune_empty_maps(value):
        if not isinstance(value, dict):
            return value
        result = {key: prune_empty_maps(child) for key, child in value.items()}
        return {key: child for key, child in result.items() if child != {}}
    for config in (b, expected):
        config['services'][service]['deploy'] = prune_empty_maps(config['services'][service]['deploy'])
    assert b == expected, 'Unexpected Compose config difference'
    effective = dict(x.split('=', 1) for x in image['Config']['Env'])
    effective.update(b['services'][service].get('environment', {}))
    assert effective == settings, 'Compose differs from running environment'
    for index, f in enumerate(files):
        (p / ('before-file-' + str(index))).write_bytes(pathlib.Path(f).read_bytes())
    save(p / 'before-inspect.json', old)
    plan = {'project': project, 'service': service, 'count': len(old), 'base': base,
            'files': {f: hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest() for f in files},
            'override': str(override), 'image': image['Id'], 'old_image': old[0]['Image'],
            'role': settings['PANAKO_WORKER_MODE'], 'prepared_at': stamp()}
    save(p / 'plan.json', plan)
    print(json.dumps(plan))
    raise SystemExit(0)

plan = json.loads((p / 'plan.json').read_text())
base = plan['base']
override = pathlib.Path(plan['override'])
saved_old = json.loads((p / 'before-inspect.json').read_text())
current = [c for c in containers() if selected(c)]
assert len(current) == plan['count'] if action == 'deploy' else len(current) <= plan['count']
settings = env(saved_old[0])
required_image = plan['image'] if action == 'deploy' else plan['old_image']
assert inspect(required_image)['Id'] == required_image, 'Required image missing before drain'
assert all(env(c) == settings for c in current)
assert all(hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest() == sha for f, sha in plan['files'].items())
if action == 'deploy':
    assert not (p / 'status.json').exists(), 'Deployment already attempted'
    assert {c['Id'] for c in current} == {c['Id'] for c in saved_old}
    assert not override.exists()
else:
    assert all(c['Image'] in (plan['image'], plan['old_image']) for c in current)
others = {c['Name']: c['Id'] for c in containers() if not selected(c)}
redis = Redis(settings)
stream = 'panako.monitor.' + ('refine.' if plan['role'] == 'refine' else '') + 'request'
group = 'panako-worker-refine' if plan['role'] == 'refine' else 'panako-workers'
status_path = p / ('status.json' if action == 'deploy' else 'rollback-status.json')
status = {'phase': 'draining', 'started_at': stamp(), 'action': action, 'role': plan['role'],
          'old': [{'id': c['Id'], 'name': c['Name']} for c in current], 'frozen': []}
frozen = set()
cooperative = []
changed = False

def pending(c):
    # PEL summary lists only owners with pending work, unlike XINFO CONSUMERS
    # which also returns thousands of retired consumer names.
    summary = redis.cmd('XPENDING', stream, group)
    return sum(int(count) for name, count in (summary[3] or [])
               if name.startswith(c['Config']['Hostname'] + '-'))

try:
    if plan['role'] == 'refine':
        for c in current:
            cooperative.append(c['Id'])
            run([sys.executable, str(ROOT / 'drain-control.py'), c['Id'], 'drain'])
        status['cooperative_drain'] = cooperative[:]
        save(status_path, status)
    deadline = time.monotonic() + 1800
    while len(frozen) < len(current):
        for c in current:
            if c['Id'] in frozen:
                if pending(c):
                    run(['docker', 'unpause', c['Id']])
                    frozen.remove(c['Id'])
                continue
            if pending(c):
                continue
            assert inspect(c['Id'])['State']['Running']
            frozen.add(c['Id'])
            pause_container(c['Id'])
            # Let an already-blocked XREADGROUP finish before considering the
            # frozen consumer quiescent. Any assigned task resumes normally.
            time.sleep(max(1, int(settings.get('REDIS_BLOCK_MS', '5000')) / 1000 + 1))
            if pending(c):
                run(['docker', 'unpause', c['Id']])
                frozen.remove(c['Id'])
        status['frozen'] = sorted(frozen)
        save(status_path, status)
        if time.monotonic() > deadline:
            raise RuntimeError('Idle drain deadline reached; active tasks preserved')
        if len(frozen) < len(current):
            time.sleep(1 if cooperative else .02)
    assert all(pending(c) == 0 for c in current)
    status['pending_before_recreate'] = {c['Config']['Hostname']: pending(c) for c in current}
    assert not any(status['pending_before_recreate'].values())
    status['phase'] = 'recreating'
    save(status_path, status)
    if action == 'deploy':
        override.write_bytes((p / 'proposal.json').read_bytes())
        extra = ['-f', str(override)]
    else:
        extra = ['-f', str(p / 'rollback-override.json')]
        (p / 'rollback-override.json').write_text(json.dumps({'services': {
            service: {'image': plan['old_image'], 'deploy': {'replicas': plan['count']}}}}))
    changed = True
    run(base + extra + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                        '--scale', service + '=' + str(plan['count']), service])
    frozen.clear()
    new = [c for c in containers() if selected(c)]
    assert len(new) == plan['count']
    for c in new:
        assert env(c) == settings
        for key in ['Memory', 'MemorySwap', 'NanoCpus', 'CpuQuota', 'CpuPeriod', 'CpusetCpus',
                    'RestartPolicy', 'NetworkMode', 'PortBindings']:
            assert c['HostConfig'][key] == saved_old[0]['HostConfig'][key], key
        expected_sha = NEW if action == 'deploy' else OLD
        assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == expected_sha
        for _ in range(60):
            if healthy(c):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Health check failed')
    assert all(inspect(name)['Id'] == cid and inspect(name)['State']['Running'] for name, cid in others.items())
    if action == 'rollback':
        override.write_bytes((p / 'rollback-override.json').read_bytes())
    status.update(phase='healthy' if action == 'deploy' else 'rolled_back', healthy_at=stamp(),
                  new=[{'id': c['Id'], 'name': c['Name']} for c in new], frozen=[], unrelated_unchanged=True,
                  old_pending_after={c['Config']['Hostname']: pending(c) for c in current})
    new_hostnames = {c['Config']['Hostname'] for c in new}
    status['reused_consumer_hostnames'] = sorted(new_hostnames & {c['Config']['Hostname'] for c in current})
    status['retired_consumer_pending_after'] = {c['Config']['Hostname']: pending(c)
                                              for c in current if c['Config']['Hostname'] not in new_hostnames}
    save(status_path, status)
    print(json.dumps(status))
except BaseException as error:
    for cid in frozen:
        subprocess.run(['docker', 'unpause', cid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not changed:
        restored = {}
        for cid in cooperative:
            result = subprocess.run([sys.executable, str(ROOT / 'drain-control.py'), cid, 'resume'], capture_output=True)
            (p / ('drain-resume-' + cid[:12] + '.log')).write_bytes(result.stdout + result.stderr)
            restored[cid] = result.returncode
        status['cooperative_resume_exit'] = restored
    status.update(phase='failed', error=type(error).__name__, changed=changed)
    save(status_path, status)
    if changed and action == 'deploy':
        result = subprocess.run([sys.executable, __file__, 'rollback', project, service], capture_output=True)
        (p / 'rollback-run.log').write_bytes(result.stdout + result.stderr)
        status['automatic_rollback_exit'] = result.returncode
        save(status_path, status)
    raise
