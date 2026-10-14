"""Local bounded observer. Exits after all seven workers complete real tasks."""
import concurrent.futures
import datetime
import json
import os
import pathlib
import subprocess
import time

os.umask(0o077)
p = pathlib.Path('build/rebalance-20261014')
(p / 'observer.pid').write_text(str(os.getpid()))
hosts = ['46.224.159.198', '91.99.52.184', '157.180.26.0']
deadline = time.monotonic() + 1200


def check(host):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', 'bekzat@' + host,
        'python3 /home/bekzat/rebalance-20261014/observe.py'], capture_output=True, text=True, timeout=45)
    if result.returncode:
        (p / (host + '-observer-error.log')).write_text(result.stderr)
        return {'host': host, 'phase': 'observer_error', 'new': []}
    value = json.loads(result.stdout)
    value['host'] = host
    (p / (host + '-latest.json')).write_text(json.dumps(value, indent=2))
    return value


i = 0
while time.monotonic() < deadline and not (p / 'stop-observer').exists():
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        snapshots = list(pool.map(check, hosts))
    (p / ('observation-' + str(i) + '.json')).write_text(json.dumps(snapshots))
    workers = [c for s in snapshots for c in s['new']]
    print(json.dumps({'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'phases': {s['host']: s['phase'] for s in snapshots}, 'running': len(workers),
        'completed': sum(bool(c['verified_responses']) for c in workers),
        'errors': sum(sum(c['errors'].values()) for c in workers)}), flush=True)
    if (len(workers) == 7 and all(s['phase'] == 'healthy' for s in snapshots)
        and all(c['verified_responses'] and all(x['status'] == 'ok' for x in c['verified_responses'])
            and not c['restarts'] and not c['oom'] and not any(c['errors'].values()) for c in workers)):
        (p / 'acceptance.json').write_text(json.dumps({'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'passed': True, 'snapshots': snapshots}, indent=2))
        print('All seven workers passed real-task acceptance', flush=True)
        break
    i += 1
    time.sleep(15)
else:
    print('Observer stopped or deadline reached; inspect latest snapshots', flush=True)
