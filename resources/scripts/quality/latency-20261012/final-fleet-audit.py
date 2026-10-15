"""Collect final read-only fleet evidence after rollout; emit no credentials."""
import concurrent.futures
import datetime
import json
import math
import os
import pathlib
import statistics
import subprocess

os.umask(0o077)
scripts = pathlib.Path(__file__).resolve().parent
evidence = scripts.parents[3] / 'build/latency-20261012'
fleet = evidence / 'fleet'
prepared = json.loads((fleet / 'preparation.json').read_text())
rollout = json.loads((fleet / 'rollout.json').read_text())
observer_stop = json.loads((fleet / 'observer-stop.json').read_text())
assert rollout['phase'] == 'waves_complete', 'Finish rollout before final acceptance'
candidate = '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'


def ssh(host, command, body=None):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20',
        '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=3', 'bekzat@' + host,
        command], input=body, capture_output=True, text=True, timeout=150)
    assert result.returncode == 0, 'Read-only collection failed on ' + host
    return json.loads(result.stdout)


def collect(host):
    inventory = ssh(host, 'python3 -', (scripts / 'fleet-audit.py').read_text())
    observation = ssh(host, 'cat /home/bekzat/latency-fleet-20261012/observation.json')
    (fleet / (host + '-final-inventory.json')).write_text(json.dumps(inventory, indent=2))
    (fleet / (host + '-final-observation.json')).write_text(json.dumps(observation, indent=2))
    return host, inventory, observation


def stats(values):
    values = sorted(values)
    return {'n': len(values), 'mean_ms': round(statistics.mean(values), 2),
        'p95_ms': values[math.ceil(len(values) * .95) - 1], 'max_ms': max(values)} if values else {'n': 0}


with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    snapshots = list(pool.map(collect, prepared))
pair = ssh('167.233.61.228', 'python3 -', (scripts / 'worker-audit.py').read_text())
(fleet / 'pair-final.json').write_text(json.dumps(pair, indent=2))
errors = []
workers = []
samples = {'stage1': {}, 'refine': {}}
warnings = []
for host, inventory, observation in snapshots:
    before = json.loads((fleet / (host + '.json')).read_text())
    old = {(w['name'], w['role']): w for w in before['workers'] if '-store-' not in w['name']}
    new = {(w['name'], w['role']): w for w in inventory['workers']}
    if old.keys() != new.keys():
        errors.append(host + ': worker identities/count changed')
    for key, worker in new.items():
        workers.append(dict(worker, host=host))
        if worker['jar_sha256'] != candidate or worker['restarts'] or worker['oom']:
            errors.append(host + ':' + worker['name'] + ': artifact/state')
        for field in ['redis_host', 'db_host', 'jdbc_host', 'jdbc_port', 'windows', 'threads',
                      'heap', 'memory_mib', 'cpus', 'project', 'service', 'mount_count', 'port_args']:
            if key in old and field in old[key] and worker[field] != old[key][field]:
                errors.append(host + ':' + worker['name'] + ': changed ' + field)
        fatal = ['Redis monitor failed', 'Redis worker error', 'Redis task heartbeat failed',
                 'Redis task not acknowledged', 'java.lang.OutOfMemoryError', 'Window processing failed']
        if any(worker['errors'][k] for k in fatal):
            errors.append(host + ':' + worker['name'] + ': worker exception')
        if any(worker['errors'].values()):
            warnings.append({'host': host, 'name': worker['name'], 'counters': worker['errors']})
    age = (datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromisoformat(observation['at'])).total_seconds()
    stop = observer_stop.get(host, {})
    frozen_ok = (bool(stop.get('finished_at'))
        and observation.get('watcher_finished_at') == stop['finished_at']
        and observation['at'] == stop['at']
        and not stop.get('watcher_error') and not stop.get('old_pending')
        and 0 <= (datetime.datetime.fromisoformat(stop['finished_at'])
                  - datetime.datetime.fromisoformat(observation['at'])).total_seconds() <= 60)
    if observation['watcher_error'] or observation['old_pending'] or (age > 120 and not frozen_ok):
        errors.append(host + ': observer/old pending/stale snapshot')
    for result in observation['responses'].values():
        if result['status'] != 'ok':
            errors.append(host + ': unsuccessful candidate response ' + result['response_id'])
        if result['status'] == 'ok' and result['matched_completion']:
            samples[result['role']]['10.0.0.2:' + result['request_id']] = dict(result, host=host)

deployment = json.loads((evidence / 'deployment-status.json').read_text())
after = {x['role']: int(datetime.datetime.fromisoformat(x['at']).timestamp() * 1000) for x in deployment['roles']}
excluded = {x['response_id'] for x in json.loads((evidence / 'recovery-publication.json').read_text())['results']}
for role, data in pair['roles'].items():
    if not data['running'] or data['oom'] or data['restarts']:
        errors.append('isolated pair ' + role + ': state')
    for result in data['samples']:
        if int(result['id'].split('-')[0]) < after[role] or result['id'] in excluded:
            continue
        if result['status'] != 'ok':
            errors.append('isolated pair ' + role + ': response ' + result['id'])
            continue
        request = result.get('request') or {}
        samples[role]['10.0.0.4:' + request.get('id', result['id'])] = dict(result,
            segment_seconds=request.get('segments_seconds', 0), host='167.233.61.228')

metrics = {}
for role, responses in samples.items():
    hour = [r for r in responses.values() if 3300 <= float(r['duration_seconds'] or 0) <= 3900
            and (role == 'stage1' or float(r['segment_seconds']) >= 3300)]
    metrics[role] = {'successful_requests': len(responses),
                     'near_hour': stats([r['processing_time_ms'] for r in hour])}
    if role == 'stage1':
        elapsed = []
        slow = []
        for result in hour:
            duration = result.get('request_to_response_ms')
            if duration is None and result.get('request_id') and result.get('response_id'):
                duration = int(result['response_id'].split('-')[0]) - int(result['request_id'].split('-')[0])
            if duration is None: continue
            elapsed.append(duration)
            if duration > 600000:
                slow.append({'recording_id': result['recording_id'], 'host': result['host'],
                    'request_to_response_ms': duration, 'processing_time_ms': result['processing_time_ms']})
        metrics[role]['request_to_response_near_hour'] = stats(elapsed)
        metrics[role]['request_to_response_above_ten_minutes'] = slow
    if role == 'stage1' and any(r['processing_time_ms'] > 600000 for r in hour):
        errors.append('Stage-1 near-hour result exceeds ten minutes')
counts = {role: sum(w['role'] == role for w in workers) for role in ['stage1', 'refine']}
redis_counts = {ip: sum(w['redis_host'] == ip for w in workers) for ip in ['10.0.0.2', '10.0.0.4']}
if counts != {'stage1': 27, 'refine': 29} or redis_counts != {'10.0.0.2': 54, '10.0.0.4': 2}:
    errors.append('Unexpected fleet count')
out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'passed': not errors,
    'counts': counts, 'redis_counts': redis_counts, 'metrics': metrics, 'errors': errors,
    'warnings': warnings, 'samples': {k: list(v.values()) for k, v in samples.items()},
    'workers': workers}
(fleet / 'final-audit.json').write_text(json.dumps(out, indent=2))
print(json.dumps({k: out[k] for k in ['at', 'passed', 'counts', 'redis_counts', 'metrics', 'errors', 'warnings']}))
