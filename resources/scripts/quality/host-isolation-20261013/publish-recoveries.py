"""Prepare or publish only the two explicitly approved incident recoveries.

Default is read-only Redis preflight. Publication requires --publish MANIFEST_SHA.
The operator must obtain approval for this pair before using that option.
"""
import datetime
import hashlib
import json
import os
import pathlib
import sys

sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env, pairs

os.umask(0o077)
p = pathlib.Path('/home/bekzat/host-isolation-20261013')
output = p / 'recovery'
request_stream = 'panako.monitor.request'
response_stream = 'panako.monitor.response'
group = 'panako-workers'
expected = {
    '01a13592-15d3-7532-b253-03eef94d65ad': ('1791900543501-0', '1791900750507-0', '-autotune.json'),
    '01a13594-2460-78bb-a49c-216ca9aa373e': ('1791901086570-0', '1791901132631-0', '.json'),
}
assert sys.argv[1:] == [] or (len(sys.argv) == 3 and sys.argv[1] == '--publish')
publish = len(sys.argv) == 3
assert json.loads((output / 'status.json').read_text())['phase'] == 'complete'
comparison = json.loads((output / 'comparison-status.json').read_text())
assert comparison['phase'] == 'complete' and comparison['recognition_identical']
requests = json.loads((p / 'recovery-requests-private.json').read_text())
manifest_file = output / 'publication-manifest.json'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def save(name, value):
    file = output / name
    temporary = file.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2))
    temporary.replace(file)


def id_tuple(mid):
    return tuple(map(int, mid.split('-')))


manifest = []
payloads = {}
for rid, (request_id, error_id, suffix) in expected.items():
    raw = (output / (rid + suffix)).read_bytes()
    body = json.loads(raw)
    assert body['status'] == 'ok' and body['recording_id'] == rid
    assert 3300 <= body['duration_seconds'] <= 3900 and 0 < body['processing_time_ms'] <= 600000
    payloads[rid] = raw.decode()
    manifest.append({'recording_id': rid, 'request_id': request_id, 'error_id': error_id,
                     'response_sha256': digest(raw), 'processing_time_ms': body['processing_time_ms'],
                     'duration_seconds': body['duration_seconds'], 'unique_tracks_count': body['unique_tracks_count']})
if not manifest_file.exists():
    assert not publish, 'Prepare an immutable manifest before requesting publication approval'
    save(manifest_file.name, manifest)
assert json.loads(manifest_file.read_text()) == manifest
manifest_sha = digest(manifest_file.read_bytes())
if publish:
    assert sys.argv[2] == manifest_sha, 'Approved manifest changed'
settings = env(json.loads((p / 'source-private.json').read_text())['containers'][0])
assert settings['REDIS_HOST'] == '10.0.0.2' and settings['PANAKO_WORKER_MODE'] == 'stage1'
r = Redis(settings)


def scan(stream, start):
    info = pairs(r.cmd('XINFO', 'STREAM', stream))
    assert info['first-entry'] and id_tuple(info['first-entry'][0]) <= id_tuple(start), 'History gap'
    end = info['last-generated-id']
    cursor = start
    matches = {rid: [] for rid in expected}
    for _ in range(100):
        rows = r.cmd('XRANGE', stream, cursor, end, 'COUNT', 1000)
        for mid, fields in rows:
            f = pairs(fields)
            body = json.loads(f['value'])
            rid = body.get('recording_id')
            if rid in matches:
                matches[rid].append((mid, body))
        if len(rows) < 1000 or rows[-1][0] == end:
            return matches
        cursor = '(' + rows[-1][0]
    raise RuntimeError('Bounded stream scan exceeded')


for _ in range(10):
    r.cmd('WATCH', request_stream, response_stream)
    current_requests = scan(request_stream, min(x[0] for x in expected.values()))
    current_responses = scan(response_stream, min(x[1] for x in expected.values()))
    plan = []
    for entry in manifest:
        rid = entry['recording_id']
        rows = current_requests[rid]
        assert len(rows) == 1 and rows[0][0] == entry['request_id'], 'A new request for this recording exists'
        assert rows[0][1] == requests[rid][0]['body'], 'Original request changed'
        assert not r.cmd('XPENDING', request_stream, group, entry['request_id'], entry['request_id'], 1)
        history = current_responses[rid]
        assert history, 'Known error response missing'
        latest_id, latest = history[-1]
        skip = latest.get('status') == 'ok'
        if not skip:
            assert latest_id == entry['error_id'] and latest.get('status') == 'error'
            assert str(latest.get('error', '')).startswith('Window processing failed')
        plan.append({**entry, 'action': 'skip_already_ok' if skip else 'publish', 'latest_response_id': latest_id})
    save('publication-preflight.json', {'at': stamp(), 'manifest_sha256': manifest_sha, 'records': plan})
    if not publish or all(x['action'] == 'skip_already_ok' for x in plan):
        r.cmd('UNWATCH')
        print(json.dumps({'manifest_sha256': manifest_sha, 'records': plan, 'published': False}))
        break
    r.cmd('MULTI')
    selected = [x for x in plan if x['action'] == 'publish']
    for entry in selected:
        assert r.cmd('XADD', response_stream, '*', 'key', entry['recording_id'],
                     'value', payloads[entry['recording_id']]) == 'QUEUED'
    result = r.cmd('EXEC')
    if result is None:
        continue
    assert len(result) == len(selected)
    receipt = {'at': stamp(), 'manifest_sha256': manifest_sha, 'redis_host': '10.0.0.2',
               'response_stream': response_stream,
               'records': [{**entry, 'response_id': mid} for entry, mid in zip(selected, result)]}
    save('publication-receipt.json', receipt)
    print(json.dumps(receipt))
    break
else:
    raise RuntimeError('Concurrent updates prevented publication; no transaction committed')
