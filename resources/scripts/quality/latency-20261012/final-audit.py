"""Read-only final evidence for the isolated release; emit no credentials."""
import datetime
import json
import pathlib
import subprocess
import sys

sys.path.insert(0, '/home/bekzat/isolated-pair-20261009')
from redis_state import Redis, env, pairs

root = pathlib.Path('/home/bekzat/latency-20261012')
deployment = json.loads((root / 'deployment-status.json').read_text())
release = json.loads((root / 'release.json').read_text())
acceptance = json.loads((root / 'acceptance.json').read_text())
verification = json.loads(subprocess.check_output([
    'python3', '/home/bekzat/isolated-pair-20261009/verify.py']))
out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
       'verification': verification, 'acceptance_passed': acceptance['passed'],
       'roles': {}}
for item in deployment['roles']:
    role = item['role']
    container = json.loads(subprocess.check_output(['docker', 'inspect', item['new_id']]))[0]
    before = json.loads((root / ('inspect-before-' + role + '.json')).read_text())
    settings = env(container)
    assert settings == env(before)
    assert all(container['HostConfig'][k] == before['HostConfig'][k]
               for k in ('Memory', 'MemorySwap', 'NanoCpus'))
    jar_hash = subprocess.check_output([
        'docker', 'exec', container['Id'], 'sha256sum', '/app/panako.jar'], text=True).split()[0]
    assert jar_hash == release['jar_sha256']
    logs = subprocess.check_output(['docker', 'logs', '--since', item['at'], container['Id']],
                                   stderr=subprocess.STDOUT, text=True)
    redis = Redis(settings)
    stream = 'panako.monitor.' + ('refine.' if role == 'refine' else '')
    groups = {suffix: [pairs(x) for x in redis.cmd('XINFO', 'GROUPS', stream + suffix)]
              for suffix in ('request', 'response')}
    wanted = {x['recording_id'] for x in acceptance['roles'][role]['responses']}
    coverage = []
    if role == 'refine':
        for mid, fields in redis.cmd('XRANGE', stream + 'request', '-', '+', 'COUNT', 5000):
            body = json.loads(pairs(fields)['value'])
            if body['recording_id'] in wanted:
                segments = body.get('segments', []) or []
                coverage.append({'request_id': mid, 'recording_id': body['recording_id'],
                                 'segments': len(segments),
                                 'segment_seconds': sum(float(x['end_seconds']) - float(x['start_seconds'])
                                                        for x in segments)})
    out['roles'][role] = {
        'container': container['Name'], 'jar_sha256': jar_hash,
        'environment_unchanged': True, 'limits_unchanged': True,
        'cpu_limit': container['HostConfig']['NanoCpus'] / 1e9,
        'memory_limit_mib': container['HostConfig']['Memory'] / 1024 ** 2,
        'redis_host': settings['REDIS_HOST'], 'clickhouse_host': settings['CLICKHOUSE_HOST'],
        'ownership_lost_warnings': logs.count('Redis task ownership lost'),
        'groups': groups, 'refine_request_coverage': coverage}
print(json.dumps(out))
