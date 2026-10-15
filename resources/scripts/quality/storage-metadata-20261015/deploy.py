"""Apply a bounded metadata hotfix after cooperative storage-worker drain."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

os.umask(0o077)
root = Path('/home/bekzat/storage-metadata-20261015')
previous = Path('/home/bekzat/storage-recovery-20261014')
name = 'panako-storage-recovery-20261014-store-1'
expected = 'f56b0f3c31ce8af43396539ea27c672ba2bc656ab25104fe44f7b6d46c5dfb2d'
base = '947e7ed08a6d15b90ec0af8cbfc58ce9dd99af25344bbd63abec2634b3d6a5c5'

def run(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=180)
    if result.returncode:
        (root / 'command-error-private.txt').write_text(result.stdout + result.stderr)
        raise RuntimeError('Command failed; details retained in protected incident folder')
    return result.stdout.strip()

def inspect(target):
    return json.loads(run(['docker', 'inspect', target]))[0]

mode = sys.argv[1]
assert mode in ('prepare', 'apply')
assert hashlib.sha256((root / 'panako.jar').read_bytes()).hexdigest() == expected
c = inspect(name)
assert run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == base
compose_path = previous / 'compose-private.json'
compose_cmd = ['docker', 'compose', '-p', 'panako-storage-recovery-20261014', '-f', str(compose_path)]
if mode == 'prepare':
    assert not (root / 'before-private.json').exists()
    (root / 'before-private.json').write_text(json.dumps(c))
    (root / 'compose-before-private.json').write_bytes(compose_path.read_bytes())
    run(['docker', 'tag', c['Image'], 'ozen-panako:storage-metadata-base-20261015'])
    (root / 'Dockerfile').write_text('FROM ozen-panako:storage-metadata-base-20261015\nCOPY panako.jar /app/panako.jar\n')
    (root / '.dockerignore').write_text('*\n!Dockerfile\n!panako.jar\n')
    run(['docker', 'build', '--pull=false', '--network=none', '-t', 'ozen-panako:storage-metadata-20261015', str(root)])
    image = inspect('ozen-panako:storage-metadata-20261015')['Id']
    (root / 'plan.json').write_text(json.dumps({'image': image, 'jar': expected, 'container': c['Id']}))
    print(json.dumps({'prepared': True, 'jar': expected}))
else:
    plan = json.loads((root / 'plan.json').read_text())
    assert c['Id'] == plan['container']
    status = json.loads(run(['python3', str(previous / 'drain-control.py'), c['Id'], 'status']))
    assert not status['thread_alive'] and not status['running_flag'], 'Active task must finish first'
    assert compose_path.read_bytes() == (root / 'compose-before-private.json').read_bytes()
    config = json.loads(compose_path.read_text())
    assert list(config['services']) == ['store']
    config['services']['store']['image'] = plan['image']
    candidate = root / 'compose-candidate-private.json'
    candidate.write_text(json.dumps(config))
    rendered_before = json.loads(run(compose_cmd + ['config', '--format', 'json']))
    rendered_after = json.loads(run(['docker', 'compose', '-p', 'panako-storage-recovery-20261014', '-f', str(candidate), 'config', '--format', 'json']))
    rendered_before['services']['store']['image'] = plan['image']
    assert rendered_before == rendered_after, 'Only the image may change'
    compose_path.write_bytes(candidate.read_bytes())
    run(compose_cmd + ['up', '-d', '--no-build', '--pull', 'never', 'store'])
    current = inspect(name)
    assert current['State']['Running'] and current['Image'] == plan['image']
    assert run(['docker', 'exec', current['Id'], 'sha256sum', '/app/panako.jar']).split()[0] == expected
    (root / 'started.json').write_text(json.dumps({'id': current['Id'], 'at': current['State']['StartedAt'], 'jar': expected}))
    print(json.dumps({'updated': True, 'jar': expected, 'restarts': current['RestartCount']}))
