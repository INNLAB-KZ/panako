"""Bounded offline retry of one download failure; no queue credentials or writes."""
import datetime, hashlib, json, os, pathlib, subprocess, time

os.umask(0o077)
p = pathlib.Path('/home/bekzat/latency-20261012')
assert not (p / 'fleet-recovery-status.json').exists()
body = json.loads((p / 'fleet-recovery-request.json').read_text())
assert body['recording_id'] == '01a0c89b-22ab-7528-b136-115265e6e015'
assert hashlib.sha256((p / 'panako.jar').read_bytes()).hexdigest() == '60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
assert hashlib.sha256((p / 'release/panako.jar').read_bytes()).hexdigest() == '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
assert not (p / 'classes/be/panako/strategy/olaf/OlafStrategy.class').exists()
c = json.loads(subprocess.check_output(['docker', 'inspect', 'panako-isolated-pair-refine-1']))[0]
state = {'phase': 'starting', 'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
         'recording_id': body['recording_id'], 'queue_publication': False, 'results': []}

def save():
    tmp = p / 'fleet-recovery-status.tmp'
    tmp.write_text(json.dumps(state, indent=2))
    tmp.replace(p / 'fleet-recovery-status.json')

def execute(name, heap, memory, args, timeout):
    result = subprocess.run(['docker', 'run', '-d', '--name', name, '--restart=no', '--network=host',
        '--cpus=3', '--memory=' + memory, '--memory-swap=' + memory,
        '--env-file', str(p / 'fleet-recovery.env'),
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
    execute('latency-fleet-recovery-download-20261012', '256m', '384m',
            ['-cp', '/input/classes:/input/panako.jar', 'be.panako.http.DownloadProbe',
             '/input/fleet-recovery.url', '/output/fleet-recovery.audio'], 900)
    segments = ','.join(str(int(s['start_seconds'])) + ':' + str(int(s['end_seconds'])) for s in body['segments'])
    for variant, jar in [('candidate', '/input/release/panako.jar')]:
        memory = {s.split(':')[0]: int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()}
        assert memory['MemAvailable'] > 7 * 1024 ** 2
        state['phase'] = variant
        save()
        output = 'fleet-recovery-' + variant + '.json'
        execute('latency-fleet-recovery-' + variant + '-20261012', '3584m', '4608m',
                ['-cp', '/input/classes:' + jar, 'be.panako.http.OfflineProfileProbe',
                 '/output/fleet-recovery.audio', '/output/' + output, segments, body['recording_id']], 1200)
        response = json.loads((p / 'output' / output).read_text())
        assert response['status'] == 'ok' and response['recording_id'] == body['recording_id']
        state['results'].append({'variant': variant, 'processing_time_ms': response['processing_time_ms'],
                                 'unique_tracks': response['unique_tracks_count'],
                                 'response_sha256': hashlib.sha256((p / 'output' / output).read_bytes()).hexdigest()})
        save()
    state.update(phase='complete', finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 segment_seconds=sum(float(s['end_seconds']) - float(s['start_seconds']) for s in body['segments']),
                 audio_sha256=hashlib.sha256((p / 'output/fleet-recovery.audio').read_bytes()).hexdigest())
    save()
except BaseException as error:
    state.update(phase='failed', error=type(error).__name__)
    save()
    raise
finally:
    for name in ['latency-fleet-recovery-download-20261012', 'latency-fleet-recovery-candidate-20261012']:
        subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
print(json.dumps(state))
