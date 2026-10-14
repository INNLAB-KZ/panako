"""Bounded offline positive-match refine comparison; no queue credentials or writes."""
import datetime, hashlib, json, os, pathlib, subprocess, time

os.umask(0o077)
p = pathlib.Path('/home/bekzat/latency-20261012')
assert not (p / 'positive-refine-status.json').exists()
body = json.loads((p / 'positive-refine-request.json').read_text())
assert body['recording_id'] == '01a0df55-cf39-7e8a-974e-7beb2bd987b7'
assert hashlib.sha256((p / 'panako.jar').read_bytes()).hexdigest() == '60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
assert hashlib.sha256((p / 'release/panako.jar').read_bytes()).hexdigest() == '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
assert not (p / 'classes/be/panako/strategy/olaf/OlafStrategy.class').exists()
c = json.loads(subprocess.check_output(['docker', 'inspect', 'panako-isolated-pair-refine-1']))[0]
env = dict(x.split('=', 1) for x in c['Config']['Env'])
env = {k: v for k, v in env.items() if not k.startswith(('REDIS_', 'KAFKA_')) and k != 'API_KEY'}
env.update(REDIS_ENABLED='false', KAFKA_ENABLED='FALSE')
(p / 'positive-refine.env').write_text(''.join(k + '=' + v + '\n' for k, v in env.items()))
(p / 'positive-refine.url').write_text(body['audio_url'])
state = {'phase': 'starting', 'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
         'recording_id': body['recording_id'], 'queue_publication': False, 'results': []}

def save():
    tmp = p / 'positive-refine-status.tmp'
    tmp.write_text(json.dumps(state, indent=2))
    tmp.replace(p / 'positive-refine-status.json')

def execute(name, heap, memory, args, timeout):
    result = subprocess.run(['docker', 'run', '-d', '--name', name, '--restart=no', '--network=host',
        '--cpus=3', '--memory=' + memory, '--memory-swap=' + memory,
        '--env-file', str(p / 'positive-refine.env'),
        '--mount', 'type=bind,src=' + str(p) + ',dst=/input,readonly',
        '--mount', 'type=bind,src=' + str(p / 'output') + ',dst=/output', '--entrypoint', 'java', c['Image'],
        '--add-opens=java.base/java.nio=ALL-UNNAMED', '-Xmx' + heap, *args], capture_output=True, text=True)
    assert result.returncode == 0, 'Diagnostic container start failed'
    cid = result.stdout.strip()
    state['container'] = cid
    save()
    end = time.monotonic() + timeout
    while True:
        current = json.loads(subprocess.check_output(['docker', 'inspect', cid]))[0]
        if not current['State']['Running']:
            break
        if time.monotonic() > end:
            subprocess.run(['docker', 'stop', '--timeout', '10', cid], capture_output=True)
            raise RuntimeError('Diagnostic deadline')
        time.sleep(5)
    logs = subprocess.run(['docker', 'logs', cid], capture_output=True)
    (p / (name + '.log')).write_bytes(logs.stdout + logs.stderr)
    assert current['State']['ExitCode'] == 0 and not current['State']['OOMKilled']

def canonical(value):
    if isinstance(value, dict):
        return {k: canonical(v) for k, v in value.items() if k != 'processing_time_ms'}
    if isinstance(value, list):
        values = [canonical(v) for v in value]
        return sorted(values, key=lambda v: json.dumps(v, sort_keys=True)) if values and isinstance(values[0], dict) else values
    return value

try:
    state['phase'] = 'download'
    save()
    execute('latency-positive-refine-download-20261012', '256m', '384m',
            ['-cp', '/input/classes:/input/panako.jar', 'be.panako.http.DownloadProbe',
             '/input/positive-refine.url', '/output/positive-refine.audio'], 900)
    segments = ','.join(str(int(s['start_seconds'])) + ':' + str(int(s['end_seconds'])) for s in body['segments'])
    for variant, jar in [('baseline', '/input/panako.jar'), ('candidate', '/input/release/panako.jar')]:
        memory = {s.split(':')[0]: int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()}
        assert memory['MemAvailable'] > 7 * 1024 ** 2
        state['phase'] = variant
        save()
        output = 'positive-refine-' + variant + '.json'
        execute('latency-positive-refine-' + variant + '-20261012', '3584m', '4608m',
                ['-cp', '/input/classes:' + jar, 'be.panako.http.OfflineProfileProbe',
                 '/output/positive-refine.audio', '/output/' + output, segments, body['recording_id']], 1200)
        response = json.loads((p / 'output' / output).read_text())
        assert response['status'] == 'ok' and response['unique_tracks_count'] > 0
        state['results'].append({'variant': variant, 'processing_time_ms': response['processing_time_ms'],
                                 'unique_tracks': response['unique_tracks_count'],
                                 'response_sha256': hashlib.sha256((p / 'output' / output).read_bytes()).hexdigest()})
        save()
    baseline = json.loads((p / 'output/positive-refine-baseline.json').read_text())
    candidate = json.loads((p / 'output/positive-refine-candidate.json').read_text())
    state['recognition_identical'] = canonical(baseline) == canonical(candidate)
    assert state['recognition_identical'], 'Positive recognition comparison differs'
    state.update(phase='complete', finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 segment_seconds=sum(float(s['end_seconds']) - float(s['start_seconds']) for s in body['segments']),
                 audio_sha256=hashlib.sha256((p / 'output/positive-refine.audio').read_bytes()).hexdigest())
    save()
except BaseException as error:
    state.update(phase='failed', error=type(error).__name__)
    save()
    raise
print(json.dumps(state))
