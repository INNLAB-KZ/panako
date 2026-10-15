"""Read-only acceptance snapshot for the three additional isolated refine workers."""
import datetime
import json
import os
import pathlib
import re
import subprocess
import sys

os.umask(0o077)
sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env, pairs

p = pathlib.Path('/home/bekzat/isolated-refine-five-20261014')
plan = json.loads((p / 'plan.json').read_text())
since = datetime.datetime.fromisoformat(plan['at']).timestamp()
start = str(int(since * 1000)) + '-0'
state = json.loads((p / 'status.json').read_text())
ids = subprocess.check_output(['docker', 'ps', '-q'], text=True).split()
cs = json.loads(subprocess.check_output(['docker', 'inspect', *ids]))
seen_path = p / 'observed-private.json'
seen = json.loads(seen_path.read_text()) if seen_path.exists() else {}
out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'phase': state['phase'],
       'queues': {}, 'new': [], 'retired': len(list(p.glob('*-retired.json')))}
connections = {}
responses = {}
pending = {}
for c in cs:
    if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') != 'panako-isolated-refine-five-20261014':
        continue
    e = env(c)
    address = e['REDIS_HOST']
    if address not in connections:
        r = connections[address] = Redis(e)
        responses[address] = r.cmd('XRANGE', 'panako.monitor.refine.response', start, '+', 'COUNT', 10000)
        pending[address] = r.cmd('XPENDING', 'panako.monitor.refine.request', 'panako-worker-refine', '-', '+', 1000)
        out['queues'][address] = {}
        for stream in ('panako.monitor.request', 'panako.monitor.refine.request'):
            out['queues'][address][stream] = [{k: g.get(k) for k in ('name', 'pending', 'lag')}
                for g in (pairs(x) for x in r.cmd('XINFO', 'GROUPS', stream))]
    own = [x for x in pending[address] if x[1].startswith(c['Config']['Hostname'] + '-')]
    known = seen.setdefault(c['Id'], {})
    for mid, consumer, idle, deliveries in own:
        rows = connections[address].cmd('XRANGE', 'panako.monitor.refine.request', mid, mid)
        if rows:
            body = json.loads(pairs(rows[0][1])['value'])
            known[mid] = {'recording_id': body['recording_id'], 'deliveries': deliveries}
    logs = subprocess.run(['docker', 'logs', '--since', str(int(since)), c['Id']], capture_output=True, text=True)
    log = logs.stdout + logs.stderr
    times = [int(x) for x in re.findall(r'Redis monitor completed: .*? in (\d+)ms', log)]
    completed = []
    for mid, fields in responses[address]:
        body = json.loads(pairs(fields)['value'])
        if body.get('processing_time_ms') in times:
            matches = [request_id for request_id, info in known.items()
                       if info['recording_id'] == body.get('recording_id')
                       and int(request_id.split('-')[0]) <= int(mid.split('-')[0])]
            if matches:
                completed.append({'response_id': mid, 'request_ids': matches, 'recording_id': body['recording_id'],
                    'status': body.get('status'), 'processing_time_ms': body.get('processing_time_ms'),
                    'duration_seconds': body.get('duration_seconds')})
    out['new'].append({'id': c['Id'], 'name': c['Name'], 'redis': address,
        'restarts': c['RestartCount'], 'oom': c['State']['OOMKilled'], 'pending': len(own),
        'completed_ms': times, 'verified_responses': completed,
        'errors': {s: log.count(s) for s in ('Redis monitor failed', 'Redis worker error',
            'Redis task heartbeat failed', 'Redis task ownership lost', 'Window processing failed',
            'A fatal error', 'OutOfMemoryError:')}})
tmp = seen_path.with_suffix('.tmp')
tmp.write_text(json.dumps(seen))
tmp.replace(seen_path)
print(json.dumps(out))
