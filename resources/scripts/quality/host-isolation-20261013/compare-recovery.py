"""Re-run the same incident audio with destination transport settings, offline."""
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
rid = '01a13592-15d3-7532-b253-03eef94d65ad'
assert json.loads((output / 'status.json').read_text())['phase'] == 'complete'
assert not (output / 'comparison-status.json').exists()
baseline = json.loads((output / (rid + '.json')).read_text())
assert baseline['status'] == 'ok' and baseline['unique_tracks_count'] == 20
assert hashlib.sha256((output / (rid + '.audio')).read_bytes()).hexdigest() == '5fcaee301afa6ee72959dc70187d4f873c7f14058ee4684af4404d5a4a3fc534'
source = json.loads((p / 'source-private.json').read_text())['containers'][0]
name = 'incident-retry-autotune-01a13592'
command = ['docker', 'run', '-d', '--name', name, '--restart=no', '--network=host',
    '--cpus=16', '--memory=4608m', '--memory-swap=4608m',
    '--env-file', str(output / 'offline-private.env'),
    '--mount', 'type=bind,src=' + str(p / 'classes') + ',dst=/probe,readonly',
    '--mount', 'type=bind,src=' + str(output) + ',dst=/output',
    '--entrypoint', 'java', source['Image'], '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '-Xmx3584m', '-cp', '/probe:/app/panako.jar', 'be.panako.http.OfflineProfileProbe',
    '/output/' + rid + '.audio', '/output/' + rid + '-autotune.json', 'ALL', rid]
result = subprocess.run(command, capture_output=True, text=True)
assert result.returncode == 0, 'Comparison container failed to start'
cid = result.stdout.strip()
state = {'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
         'phase': 'processing', 'container': cid, 'queue_publication': False}


def save():
    (output / 'comparison-status.json').write_text(json.dumps(state, indent=2))


def canonical(value):
    if isinstance(value, dict):
        return {k: canonical(v) for k, v in value.items() if k != 'processing_time_ms'}
    if isinstance(value, list):
        values = [canonical(v) for v in value]
        return sorted(values, key=lambda v: json.dumps(v, sort_keys=True)) if values and isinstance(values[0], dict) else values
    return value


save()
try:
    end = time.monotonic() + 900
    while True:
        c = json.loads(subprocess.check_output(['docker', 'inspect', cid]))[0]
        if not c['State']['Running']:
            break
        if time.monotonic() > end:
            subprocess.run(['docker', 'stop', '--timeout', '10', cid], capture_output=True)
            raise RuntimeError('Comparison deadline')
        time.sleep(5)
    logs = subprocess.run(['docker', 'logs', cid], capture_output=True)
    (output / (name + '.log')).write_bytes(logs.stdout + logs.stderr)
    assert c['State']['ExitCode'] == 0 and not c['State']['OOMKilled']
    file = output / (rid + '-autotune.json')
    actual = json.loads(file.read_text())
    same = canonical(actual) == canonical(baseline)
    state.update(baseline_ms=baseline['processing_time_ms'], candidate_ms=actual['processing_time_ms'],
                 recognition_identical=same, unique_tracks_count=actual['unique_tracks_count'],
                 response_sha256=hashlib.sha256(file.read_bytes()).hexdigest())
    assert same, 'Recognition output differs'
    assert actual['processing_time_ms'] <= 600000, 'Destination hour processing exceeds 10 minutes'
    state.update(phase='complete', finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    save()
except BaseException as error:
    state.update(phase='failed', error=type(error).__name__)
    save()
    raise
print(json.dumps(state))
