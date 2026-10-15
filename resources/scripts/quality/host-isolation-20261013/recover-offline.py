"""Retry two incident records without Redis access or result publication."""
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import time

os.umask(0o077)
p = pathlib.Path('/home/bekzat/host-isolation-20261013')
output = p / 'recovery'
output.mkdir(mode=0o700, exist_ok=True)
status_path = output / 'status.json'
assert not status_path.exists()
source = json.loads((p / 'source-private.json').read_text())['containers'][0]
requests = json.loads((p / 'recovery-requests-private.json').read_text())
assert set(requests) == {'01a13592-15d3-7532-b253-03eef94d65ad', '01a13594-2460-78bb-a49c-216ca9aa373e'}
settings = dict(x.split('=', 1) for x in source['Config']['Env'])
settings = {k: v for k, v in settings.items() if not k.startswith(('REDIS_', 'KAFKA_')) and k != 'API_KEY'}
settings.update(REDIS_ENABLED='false', KAFKA_ENABLED='FALSE')
(output / 'offline-private.env').write_text(''.join(k + '=' + v + '\n' for k, v in settings.items()))
state = {'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
         'phase': 'starting', 'queue_publication': False, 'results': []}


def save():
    tmp = status_path.with_suffix('.tmp')
    tmp.write_text(json.dumps(state, indent=2))
    tmp.replace(status_path)


def execute(name, args, heap, memory, timeout):
    cp = subprocess.run(['docker', 'run', '-d', '--name', name, '--restart=no', '--network=host',
        '--cpus=3', '--memory=' + memory, '--memory-swap=' + memory,
        '--env-file', str(output / 'offline-private.env'),
        '--mount', 'type=bind,src=' + str(p / 'classes') + ',dst=/probe,readonly',
        '--mount', 'type=bind,src=' + str(output) + ',dst=/output',
        '--entrypoint', 'java', source['Image'], '--add-opens=java.base/java.nio=ALL-UNNAMED',
        '-Xmx' + heap, '-cp', '/probe:/app/panako.jar', *args], capture_output=True, text=True)
    assert cp.returncode == 0, 'Offline container start failed'
    cid = cp.stdout.strip()
    state['container'] = cid
    save()
    deadline = time.monotonic() + timeout
    while True:
        c = json.loads(subprocess.check_output(['docker', 'inspect', cid]))[0]
        if not c['State']['Running']:
            break
        if time.monotonic() >= deadline:
            subprocess.run(['docker', 'stop', '--timeout', '10', cid], capture_output=True)
            raise RuntimeError('Offline retry deadline')
        time.sleep(5)
    logs = subprocess.run(['docker', 'logs', cid], capture_output=True)
    (output / (name + '.log')).write_bytes(logs.stdout + logs.stderr)
    assert c['State']['ExitCode'] == 0 and not c['State']['OOMKilled']


try:
    for rid, entries in requests.items():
        assert len(entries) == 1
        body = entries[0]['body']
        assert not body.get('segments')
        state.update(recording_id=rid, phase='download')
        save()
        (output / (rid + '.url')).write_text(body['audio_url'])
        execute('incident-download-' + rid[:8], ['be.panako.http.DownloadProbe',
                '/output/' + rid + '.url', '/output/' + rid + '.audio'], '256m', '512m', 600)
        mem = {l.split(':')[0]: int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()}
        assert mem['MemAvailable'] > 7 * 1024**2
        state['phase'] = 'processing'
        save()
        execute('incident-retry-' + rid[:8], ['be.panako.http.OfflineProfileProbe',
                '/output/' + rid + '.audio', '/output/' + rid + '.json', 'ALL', rid],
                '3584m', '4608m', 1200)
        result_file = output / (rid + '.json')
        result = json.loads(result_file.read_text())
        assert result['status'] == 'ok' and result['recording_id'] == rid
        assert 3300 <= result['duration_seconds'] <= 3900
        state['results'].append({'recording_id': rid, 'request_id': entries[0]['message_id'],
            'duration_seconds': result['duration_seconds'], 'processing_time_ms': result['processing_time_ms'],
            'unique_tracks_count': result['unique_tracks_count'],
            'response_sha256': hashlib.sha256(result_file.read_bytes()).hexdigest(),
            'audio_sha256': hashlib.sha256((output / (rid + '.audio')).read_bytes()).hexdigest()})
        save()
    state.update(phase='complete', finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    save()
except BaseException as error:
    state.update(phase='failed', error=type(error).__name__)
    save()
    raise
print(json.dumps(state))
