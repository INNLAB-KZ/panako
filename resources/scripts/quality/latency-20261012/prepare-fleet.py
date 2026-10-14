"""Prepare independent hosts with bounded concurrency; never starts live deployment."""
import concurrent.futures, json, os, pathlib, shlex, subprocess

os.umask(0o077)
base = pathlib.Path(__file__).resolve().parent
workspace = base.parents[3]
evidence = workspace / 'build/latency-20261012/fleet'
remote = '/home/bekzat/latency-fleet-20261012'
files = [base / 'fleet-release.py', base / 'fleet-watch.py',
         workspace / 'build/latency-20261012/overlay.zip',
         workspace / 'build/release-46-20261009/private/redis_state.py']
state = {}

def command(args, timeout=240):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout

def prepare(path):
    host = path.stem
    groups = sorted({(w['project'], w['service']) for w in json.loads(path.read_text())['workers']
                     if w['redis_host'] == '10.0.0.2' and '-store-' not in w['name']})
    ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', 'bekzat@' + host]
    try:
        command(ssh + ['mkdir -p ' + remote + ' && chmod 700 ' + remote])
        command(['scp', '-q', *map(str, files), 'bekzat@' + host + ':' + remote + '/'])
        result = []
        for project, service in groups:
            if host == '167.233.61.250' and service == 'panako-api-memory':
                result.append({'project': project, 'service': service, 'already_prepared': True})
                continue
            output = command(ssh + [shlex.join(['python3', remote + '/fleet-release.py', 'prepare', project, service])])
            plan = json.loads(output)
            result.append({k: plan[k] for k in ['project', 'service', 'count', 'image', 'role']})
        return host, {'ok': True, 'groups': result}
    except Exception as error:
        (evidence / (host + '-prepare-error.log')).write_text(str(error))
        return host, {'ok': False, 'error': type(error).__name__}

paths = [f for f in evidence.glob('*.json') if f.stem.count('.') == 3 and '-' not in f.stem]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
    for host, result in executor.map(prepare, paths):
        state[host] = result
        (evidence / 'preparation.json').write_text(json.dumps(state, indent=2))
        print(json.dumps({'host': host, **result}), flush=True)
