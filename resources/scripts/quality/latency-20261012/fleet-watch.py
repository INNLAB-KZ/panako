"""Read-only observation of candidate containers and attributed stream responses."""
import datetime, hashlib, json, os, pathlib, re, subprocess, time, urllib.parse
from redis_state import Redis, env, pairs

os.umask(0o077)
root = pathlib.Path('/home/bekzat/latency-fleet-20261012')
statefile = root / 'observation.json'
out = json.loads(statefile.read_text()) if statefile.exists() else {'seen': {}, 'responses': {}, 'cursors': {}}
started = time.monotonic()

def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def run(args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, text=True)

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

while time.monotonic() - started < 12 * 3600 and not (root / 'STOP-WATCH').exists():
    try:
        roles = {}
        old_hosts = {}
        live = {}
        for f in root.glob('*/status.json'):
            d = json.loads(f.read_text())
            if d['phase'] != 'healthy':
                continue
            before = json.loads((f.parent / 'before-inspect.json').read_text())
            old_hosts.setdefault(d['role'], set()).update(c['Config']['Hostname'] for c in before)
            for entry in d['new']:
                c = json.loads(run(['docker', 'inspect', entry['id']]))[0]
                e = env(c)
                role = d['role']
                logs = run(['docker', 'logs', '--since', d['healthy_at'], '--tail', '100000', c['Id']])
                completions = {(digest(name), int(ms)) for name, ms in re.findall(
                    r'Redis monitor completed: (.*?) in (\d+)ms', logs)}
                live[c['Id']] = {'name': c['Name'], 'role': role, 'running': c['State']['Running'],
                    'restarts': c['RestartCount'], 'oom': c['State']['OOMKilled'],
                    'completions': len(completions),
                    'errors': {k: logs.count(k) for k in ['Redis monitor failed', 'Redis worker error',
                        'Redis task heartbeat failed', 'Redis task not acknowledged',
                        'java.lang.OutOfMemoryError', 'Window processing failed']},
                    'ownership_warnings': logs.count('Redis task ownership lost'),
                    'download_retries': logs.count('Download attempt'),
                    'download_fallbacks': logs.count('audio_origin_download_failed')}
                group = 'panako-worker-refine' if role == 'refine' else 'panako-workers'
                prefix = 'panako.monitor.' + ('refine.' if role == 'refine' else '')
                if role not in roles:
                    roles[role] = {'redis': Redis(e), 'group': group, 'prefix': prefix, 'containers': []}
                roles[role]['containers'].append((c, completions))
                out['cursors'].setdefault(role, str(int(datetime.datetime.fromisoformat(d['healthy_at']).timestamp() * 1000)) + '-0')
        out['pending'] = []
        out['old_pending'] = []
        out['groups'] = {}
        for role, cfg in roles.items():
            r, prefix, group = cfg['redis'], cfg['prefix'], cfg['group']
            consumers = [pairs(x) for x in r.cmd('XINFO', 'CONSUMERS', prefix + 'request', group)]
            live_hosts = {c['Config']['Hostname'] for c, _ in cfg['containers']}
            out['old_pending'] += [{'role': role, 'consumer': c['name'], 'pending': c['pending']}
                                   for c in consumers if c['pending']
                                   and any(c['name'].startswith(h + '-') for h in old_hosts.get(role, set()))
                                   and not any(c['name'].startswith(h + '-') for h in live_hosts)]
            completions_by_id = {c['Id']: values for c, values in cfg['containers']}
            for c, completions in cfg['containers']:
                for consumer in consumers:
                    if not consumer['name'].startswith(c['Config']['Hostname'] + '-'):
                        continue
                    for row in r.cmd('XPENDING', prefix + 'request', group, '-', '+', 10, consumer['name']):
                        key = role + ':' + row[0]
                        out['pending'].append({'key': key, 'container': c['Id'], 'idle_ms': row[2], 'deliveries': row[3]})
                        if key in out['seen']:
                            continue
                        request = r.cmd('XRANGE', prefix + 'request', row[0], row[0])
                        if not request:
                            continue
                        body = json.loads(pairs(request[0][1])['value'])
                        filename = body.get('filename') or urllib.parse.unquote(urllib.parse.urlsplit(body['audio_url']).path).rsplit('/', 1)[-1].split('?')[0] or 'audio.mp3'
                        segments = body.get('segments') or []
                        out['seen'][key] = {'request_id': row[0], 'role': role, 'recording_id': body['recording_id'],
                            'container': c['Id'], 'filename_sha256': digest(filename), 'first_seen': stamp(),
                            'segment_seconds': sum(float(s['end_seconds']) - float(s['start_seconds']) for s in segments)}
            # Keep unmatched responses briefly too: logs may trail XADD by milliseconds.
            rows = r.cmd('XRANGE', prefix + 'response', '(' + out['cursors'][role], '+', 'COUNT', 2000)
            for mid, fields in rows:
                body = json.loads(pairs(fields)['value'])
                candidates = [x for x in out['seen'].values() if x['role'] == role
                              and x['recording_id'] == body.get('recording_id')
                              and int(x['request_id'].split('-')[0]) <= int(mid.split('-')[0])]
                if candidates:
                    seen = max(candidates, key=lambda x: tuple(map(int, x['request_id'].split('-'))))
                    out['responses'][role + ':' + mid] = {**seen, 'response_id': mid,
                        **{k: body.get(k) for k in ['status', 'duration_seconds', 'processing_time_ms']}}
                out['cursors'][role] = mid
            for response in out['responses'].values():
                if response['role'] == role:
                    response['matched_completion'] = (response['filename_sha256'], response['processing_time_ms']) in completions_by_id.get(response['container'], set())
            out['groups'][role] = {suffix: [pairs(x) for x in r.cmd('XINFO', 'GROUPS', prefix + suffix)]
                                   for suffix in ['request', 'response']}
            r.f.close()
            r.s.close()
        out.update(at=stamp(), containers=live, watcher_error=None)
    except Exception as error:
        out.update(at=stamp(), watcher_error=type(error).__name__)
    temp = statefile.with_suffix('.tmp')
    temp.write_text(json.dumps(out, indent=2))
    temp.replace(statefile)
    time.sleep(10)
out['watcher_finished_at'] = stamp()
statefile.write_text(json.dumps(out, indent=2))
