"""Read-only Stage-1 timing/PEL and replacement-container checks."""
import datetime
import json
import pathlib
import re
import statistics
import subprocess
import sys
import time

sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env, pairs

PROJECTS = {'panako-stage1-evac-20261013', 'panako-stage1-priority-20261013'}
since = float(sys.argv[1])
ids = subprocess.check_output(['docker', 'ps', '-q'], text=True).split()
cs = json.loads(subprocess.check_output(['docker', 'inspect', *ids]))
worker = next(c for c in cs if env(c).get('REDIS_HOST') == '10.0.0.2'
              and env(c).get('PANAKO_WORKER_MODE') in ('stage1', 'refine'))
r = Redis(env(worker))
group = next(pairs(g) for g in r.cmd('XINFO', 'GROUPS', 'panako.monitor.request')
             if pairs(g)['name'] == 'panako-workers')
pending = r.cmd('XPENDING', 'panako.monitor.request', 'panako-workers', '-', '+', 1000)


def payload(fields):
    f = pairs(fields)
    for key in ('value', 'payload', 'data'):
        if key in f:
            try:
                return json.loads(f[key])
            except (ValueError, TypeError):
                pass
    return {}


start_id = str(int(since * 1000)) + '-0'
requests = r.cmd('XRANGE', 'panako.monitor.request', start_id, '+', 'COUNT', 10000)
responses = r.cmd('XRANGE', 'panako.monitor.response', start_id, '+', 'COUNT', 10000)
by_rid = {}
for mid, fields in requests:
    body = payload(fields)
    by_rid.setdefault(body.get('recording_id'), []).append((mid, body))
times = []
end_to_end = []
statuses = {}
response_by_rid = {}
for mid, fields in responses:
    body = payload(fields)
    statuses[str(body.get('status'))] = statuses.get(str(body.get('status')), 0) + 1
    response_by_rid.setdefault(body.get('recording_id'), []).append((mid, body))
    duration = body.get('duration_seconds')
    ms = body.get('processing_time_ms')
    if body.get('status') == 'ok' and isinstance(duration, (int, float)) and 3300 <= duration <= 3900:
        if isinstance(ms, (int, float)):
            times.append(ms)
        matches = by_rid.get(body.get('recording_id'), [])
        if len(matches) == 1:
            delay = int(mid.split('-')[0]) - int(matches[0][0].split('-')[0])
            if delay >= 0:
                end_to_end.append(delay)


def summary(values):
    ordered = sorted(values)
    return {'count': len(values), 'mean_ms': round(statistics.mean(values)) if values else None,
            'p95_ms': ordered[min(len(ordered) - 1, int(len(ordered) * .95))] if values else None,
            'max_ms': max(values) if values else None, 'above_600s': sum(x > 600000 for x in values)}


out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'since_epoch': since,
       'stage1': {k: group.get(k) for k in ('lag', 'pending')},
       'requests': len(requests), 'response_statuses': statuses,
       'hour_processing': summary(times), 'hour_request_to_response': summary(end_to_end),
       'pending_idle_over_20m': sum(int(p[2]) > 1200000 for p in pending), 'replacements': []}
state_path = pathlib.Path('/home/bekzat/host-isolation-20261013/observed-private.json')
seen = json.loads(state_path.read_text()) if state_path.exists() else {}
for c in cs:
    if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') not in PROJECTS:
        continue
    key = c['Id']
    known = seen.setdefault(key, {})
    logs = subprocess.run(['docker', 'logs', '--since', str(int(since)), c['Id']], capture_output=True, text=True)
    text = logs.stdout + logs.stderr
    completed_ms = [int(x) for x in re.findall(r'Redis monitor completed: .*? in (\d+)ms', text)]
    own = [p for p in pending if p[1].startswith(c['Config']['Hostname'] + '-')]
    for mid, consumer, idle, deliveries in own:
        rows = r.cmd('XRANGE', 'panako.monitor.request', mid, mid)
        if rows:
            body = payload(rows[0][1])
            known[mid] = {'recording_id': body.get('recording_id'), 'deliveries': int(deliveries)}
    completed = []
    for mid, info in known.items():
        for response_id, body in response_by_rid.get(info['recording_id'], []):
            if (int(response_id.split('-')[0]) >= int(mid.split('-')[0])
                    and body.get('processing_time_ms') in completed_ms):
                completed.append({'request_id': mid, 'recording_id': info['recording_id'],
                                  'status': body.get('status'), 'duration_seconds': body.get('duration_seconds'),
                                  'processing_time_ms': body.get('processing_time_ms')})
    out['replacements'].append({'name': c['Name'], 'id': key, 'restart_count': c['RestartCount'],
        'oom': c['State']['OOMKilled'], 'pending': len(own), 'observed_completions': completed,
        'log_completed_ms': completed_ms,
        'errors': {s: text.count(s) for s in ('Redis monitor failed', 'Redis worker error',
            'Redis task heartbeat failed', 'Redis task ownership lost', 'Window processing failed',
            'A fatal error', 'OutOfMemoryError:')}})
state_path.write_text(json.dumps(seen))
state_path.chmod(0o600)
print(json.dumps(out))
