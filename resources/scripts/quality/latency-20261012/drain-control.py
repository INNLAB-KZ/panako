"""Cooperative drain of one validated refine JVM; no direct Redis commands."""
import datetime, hashlib, json, os, pathlib, subprocess, sys, time

os.umask(0o077)
root = pathlib.Path('/home/bekzat/latency-fleet-20261012')
folder = root / 'drain-agent'
agent = folder / 'drain-agent.jar'
agent_sha = '7fb564fca397860a5d6decfcbfe17cda9f4a403d03e48513ed927fc1976ff4a9'
image = 'eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda'
container, mode = sys.argv[1:]
assert len(container) == 64 and all(c in '0123456789abcdef' for c in container)
assert mode in ('drain', 'status', 'resume')
assert hashlib.sha256(agent.read_bytes()).hexdigest() == agent_sha

def run(args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    if result.returncode:
        (folder / (container + '-control-error.log')).write_text(result.stdout)
        raise RuntimeError('Drain control failed; protected diagnostic log saved')
    return result.stdout.strip()

c = json.loads(run(['docker', 'inspect', container]))[0]
environment = dict(v.split('=', 1) for v in c['Config']['Env'])
assert c['State']['Running'] and not c['State']['Paused']
assert environment.get('PANAKO_WORKER_MODE') == 'refine'
assert environment.get('REDIS_HOST') == '10.0.0.2'
assert environment.get('REDIS_ENABLED', '').lower() == 'true'
assert environment.get('REDIS_WORKER_THREADS') == '1'
assert c['Config']['User'] in ('', '0', 'root')
assert run(['docker', 'exec', container, 'cat', '/proc/1/comm']) == 'java'
assert run(['docker', 'exec', container, 'sha256sum', '/app/panako.jar']).split()[0] in (
    '60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e',
    '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b')
host_pid = c['State']['Pid']
assert host_pid > 1
if mode == 'drain':
    run(['docker', 'cp', str(agent), container + ':/tmp/latency-drain-agent.jar'])
assert run(['docker', 'exec', container, 'sha256sum', '/tmp/latency-drain-agent.jar']).split()[0] == agent_sha
run(['docker', 'image', 'inspect', image])
(folder / (container + '-intent.json')).write_text(json.dumps({
    'mode': mode, 'host_pid': host_pid, 'at': datetime.datetime.now(datetime.timezone.utc).isoformat()}))
run(['docker', 'run', '--rm', '--network', 'none', '--memory', '256m', '--cpus', '1',
    '--pid=host', '--cap-add', 'SYS_PTRACE',
    '--mount', 'type=bind,src=' + str(folder) + ',dst=/helper,readonly', '--entrypoint', 'java',
    image, '--add-modules', 'jdk.attach', '-Xms16m', '-Xmx64m', '-cp', '/helper/drain-agent.jar',
    'be.panako.ops.AttachDrain', str(host_pid), '/tmp/latency-drain-agent.jar', mode])
status = json.loads(run(['docker', 'exec', container, 'cat', '/tmp/latency-drain-status.json']))
assert abs(time.time() * 1000 - status['at_ms']) < 15000 and status['registered'] == 1
if mode == 'drain': assert not status['running_flag']
if mode == 'resume': assert status['running_flag']
(folder / (container + '-status.json')).write_text(json.dumps(status, indent=2))
print(json.dumps(status))
