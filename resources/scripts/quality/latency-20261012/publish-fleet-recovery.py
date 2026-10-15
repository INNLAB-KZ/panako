"""Publish the explicitly approved single refine recovery, preserving its exact bytes."""
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys

sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env, pairs

os.umask(0o077)
assert sys.argv[1:] in ([], ['--publish'])
publish = sys.argv[1:] == ['--publish']
root = pathlib.Path('/home/bekzat/latency-fleet-20261012/download-recovery')
rid = '01a0c89b-22ab-7528-b136-115265e6e015'
request_id = '1790885676183-0'
error_id = '1791802630102-0'
digest = '4b842e6b18075b78a9ff5963b1ce5ab74264d32b9694d110b300aadd3c8a03dd'
raw = (root / 'response.json').read_bytes()
assert hashlib.sha256(raw).hexdigest() == digest
body = json.loads(raw)
assert body['recording_id'] == rid and body['status'] == 'ok'
assert body['processing_time_ms'] == 231511 and body['duration_seconds'] == 3624.2
c = json.loads(subprocess.check_output([
    'docker', 'inspect', 'refine-f2-move-20261004-refine-from-main-1']))[0]
settings = env(c)
assert settings['REDIS_HOST'] == '10.0.0.2'
redis = Redis(settings)
request = 'panako.monitor.refine.request'
response = 'panako.monitor.refine.response'
group = 'panako-worker-refine'


def save(name, value):
    temp = root / (name + '.tmp')
    temp.write_text(json.dumps(value, indent=2))
    temp.replace(root / (name + '.json'))


def id_tuple(value):
    return tuple(map(int, value.split('-')))


for attempt in range(10):
    redis.cmd('WATCH', request, response)
    rows = redis.cmd('XRANGE', request, request_id, request_id)
    assert len(rows) == 1, 'Original request unavailable'
    original = json.loads(pairs(rows[0][1])['value'])
    original_digest = hashlib.sha256(json.dumps(
        original, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    assert original_digest == (root / 'request-sha256').read_text()
    assert not redis.cmd('XPENDING', request, group, request_id, request_id, 1)
    info = pairs(redis.cmd('XINFO', 'STREAM', response))
    assert info['first-entry'] and id_tuple(info['first-entry'][0]) <= id_tuple(error_id), 'Response history gap'
    end = info['last-generated-id']
    cursor = error_id
    matching = []
    for page in range(100):
        rows = redis.cmd('XRANGE', response, cursor, end, 'COUNT', 1000)
        for mid, fields in rows:
            value = json.loads(pairs(fields)['value'])
            if value.get('recording_id') == rid:
                matching.append((mid, value))
        if len(rows) < 1000 or rows[-1][0] == end:
            break
        cursor = '(' + rows[-1][0]
    else:
        raise RuntimeError('Response scan limit reached')
    assert matching, 'Known error missing from history'
    latest_id, latest = matching[-1]
    plan = {'recording_id': rid, 'request_id': request_id,
            'redis_host': '10.0.0.2', 'response_stream': response,
            'previous_response_id': latest_id, 'previous_status': latest['status'],
            'response_sha256': digest, 'processing_time_ms': 231511,
            'action': 'skip_already_ok' if latest['status'] == 'ok' else 'publish'}
    save('publication-plan', plan)
    if not publish or plan['action'] == 'skip_already_ok':
        redis.cmd('UNWATCH')
        print(json.dumps(plan))
        break
    assert latest_id == error_id and latest['status'] == 'error'
    assert latest.get('error') == 'Download failed: Read timed out'
    assert not (root / 'publication.json').exists(), 'Publication receipt already exists'
    redis.cmd('MULTI')
    assert redis.cmd('XADD', response, '*', 'key', rid, 'value', raw.decode()) == 'QUEUED'
    result = redis.cmd('EXEC')
    if result is None:
        continue
    assert len(result) == 1 and isinstance(result[0], str)
    receipt = {**plan, 'response_id': result[0],
               'published_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    save('publication', receipt)
    print(json.dumps(receipt))
    break
else:
    raise RuntimeError('Concurrent stream updates prevented publication; no write committed')
