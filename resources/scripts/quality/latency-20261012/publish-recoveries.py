"""Publish two explicitly approved baseline recovery responses, with race checks."""
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys

sys.path.insert(0, '/home/bekzat/isolated-pair-20261009')
from redis_state import Redis, env, pairs

os.umask(0o077)
root = pathlib.Path('/home/bekzat/latency-20261012')
assert sys.argv[1:] in ([], ['--publish'])
publish = sys.argv[1:] == ['--publish']
state = json.loads((root / 'status.json').read_text())
assert state['phase'] == 'complete' and len(state['results']) == 2
hashes = {'stage1': '6cdad317ed994d2a9a2e8fdf8aac9768ee34425f958994ad7d9ba2f1087bf19f',
          'refine': '628da4303b7c5ec41c5db3fbd317f0cd283ef735b927200e251bf8bab471203f'}
logpath = root / 'recovery-publication.json'
log = json.loads(logpath.read_text()) if logpath.exists() else {'results': []}
plan = []
for item in state['results']:
    role, rid, mid = item['role'], item['recording_id'], item['request_id']
    raw = (root / 'output' / (role + '-response.json')).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    assert digest == hashes[role]
    body = json.loads(raw)
    assert body['status'] == 'ok' and body['recording_id'] == rid
    assert body['processing_time_ms'] == item['processing_time_ms']
    container = json.loads(subprocess.check_output([
        'docker', 'inspect', 'panako-isolated-pair-' + role + '-1']))[0]
    settings = env(container)
    assert settings['REDIS_HOST'] == '10.0.0.4'
    redis = Redis(settings)
    prefix = 'panako.monitor.' + ('refine.' if role == 'refine' else '')
    request, response = prefix + 'request', prefix + 'response'
    group = 'panako-workers' if role == 'stage1' else 'panako-worker-refine'
    # WATCH changes no stream entries and prevents a publication race.
    redis.cmd('WATCH', request, response)
    rows = redis.cmd('XRANGE', request, mid, mid)
    assert len(rows) == 1
    original = json.loads(pairs(rows[0][1])['value'])
    assert original == json.loads((root / (role + '-request.json')).read_text())
    assert not redis.cmd('XPENDING', request, group, mid, mid, 1)
    recent_requests = redis.cmd('XRANGE', request, '(' + mid, '+', 'COUNT', 10000)
    assert len(recent_requests) < 10000
    assert not any(json.loads(pairs(fields)['value']).get('recording_id') == rid
                   for _, fields in recent_requests), 'Newer request exists; re-evaluate recovery'
    responses = redis.cmd('XRANGE', response, mid, '+', 'COUNT', 10000)
    assert len(responses) < 10000
    matching = [(eid, json.loads(pairs(fields)['value'])) for eid, fields in responses
                if json.loads(pairs(fields)['value']).get('recording_id') == rid]
    if not matching:
        # Retention can remove the old error. Require overlapping saved/current
        # history so a newer successful response cannot fall into an unseen gap.
        history = json.loads((root / 'recovery-history.json').read_text())
        info = pairs(redis.cmd('XINFO', 'STREAM', response))
        audit_ms = int(datetime.datetime.fromisoformat(history['at']).timestamp() * 1000)
        assert info['first-entry'] and int(info['first-entry'][0].split('-')[0]) <= audit_ms
        prior = [x for x in history['roles'][role]
                 if x['recording_id'] == rid and int(x['id'].split('-')[0]) >= int(mid.split('-')[0])]
        assert prior and prior[-1]['status'] == 'error', 'Recovery history requires review'
        matching = [(prior[-1]['id'], prior[-1])]
    latest_id, latest = matching[-1]
    entry = {'role': role, 'recording_id': rid, 'previous_response_id': latest_id,
             'previous_status': latest['status'], 'response_sha256': digest,
             'action': 'skip_already_ok' if latest['status'] == 'ok' else 'publish'}
    plan.append(entry)
    if entry['action'] == 'skip_already_ok' or not publish:
        redis.cmd('UNWATCH')
        continue
    assert latest['status'] == 'error'
    assert not any(x['recording_id'] == rid for x in log['results']), 'Recorded publication conflicts with latest response'
    redis.cmd('MULTI')
    assert redis.cmd('XADD', response, '*', 'key', rid, 'value', raw.decode()) == 'QUEUED'
    result = redis.cmd('EXEC')
    assert result is not None and len(result) == 1, 'Stream changed; rerun preflight'
    log['results'].append({'role': role, 'recording_id': rid, 'response_id': result[0],
                           'response_sha256': digest,
                           'at': datetime.datetime.now(datetime.timezone.utc).isoformat()})
    temp = logpath.with_suffix('.tmp')
    temp.write_text(json.dumps(log, indent=2))
    temp.replace(logpath)
(root / 'recovery-publication-plan.json').write_text(json.dumps(plan, indent=2))
print(json.dumps({'publish_requested': publish, 'plan': plan, 'publication': log}))
