"""Linux/JRE integration fixture; no production consumer or Redis connection."""
import json, os, pathlib, subprocess, time

os.umask(0o077)
root = pathlib.Path('/home/bekzat/latency-fleet-20261012')
p = root / 'drain-agent'
image = 'eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda'
plan = json.loads((root / 'refine-c-move-20261006__refine-from-main/plan.json').read_text())
name = 'latency-java-drain-fixture-20261012'
assert subprocess.run(['docker', 'inspect', name], capture_output=True).returncode != 0

def run(args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        (p / 'linux-test-error.log').write_text(result.stdout)
        raise RuntimeError(result.stdout[-2000:])
    return result.stdout.strip()

cid = run(['docker', 'create', '--name', name, '--network', 'none', '--memory', '128m',
    '--cpus', '0.5', '-e', 'JAVA_TOOL_OPTIONS=-Xms16m -Xmx32m', '--entrypoint', 'java',
    plan['old_image'], '-cp', '/tmp/drain-fixture.jar', 'be.panako.http.PanakoRedisStreamWorker'])

def read(path='/tmp/drain-fixture.json'):
    return json.loads(run(['docker', 'exec', cid, 'cat', path]))

def wait_for(predicate):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            data = read()
            if predicate(data): return data
        except RuntimeError:
            pass
        time.sleep(.1)
    raise RuntimeError('Fixture observation deadline')

def attach(mode):
    host_pid = json.loads(run(['docker', 'inspect', cid]))[0]['State']['Pid']
    assert host_pid > 1
    run(['docker', 'run', '--rm', '--network', 'none', '--memory', '256m', '--cpus', '1',
        '--pid=host', '--cap-add', 'SYS_PTRACE',
        '--mount', 'type=bind,src=' + str(p) + ',dst=/helper,readonly', '--entrypoint', 'java',
        image, '--add-modules', 'jdk.attach', '-Xms16m', '-Xmx64m', '-cp', '/helper/drain-agent.jar',
        'be.panako.ops.AttachDrain', str(host_pid), '/tmp/latency-drain-agent.jar', mode])

try:
    run(['docker', 'cp', str(p / 'drain-fixture.jar'), cid + ':/tmp/drain-fixture.jar'])
    run(['docker', 'cp', str(p / 'drain-agent.jar'), cid + ':/tmp/latency-drain-agent.jar'])
    run(['docker', 'start', cid])
    before = wait_for(lambda d: d['heartbeats'] >= 3)
    attach('drain')
    time.sleep(.5)
    middle = read()
    assert middle['heartbeats'] > before['heartbeats'] and not middle['interrupted']
    drained = wait_for(lambda d: d['started'] == d['completed'])
    time.sleep(.5)
    assert read() == drained
    attach('status')
    assert not read('/tmp/latency-drain-status.json')['thread_alive']
    attach('resume')
    resumed = wait_for(lambda d: d['started'] > drained['started'])
    attach('drain')
    attach('resume')
    wait_for(lambda d: d['started'] > resumed['started'])
    attach('drain')
    done = wait_for(lambda d: d['started'] == d['completed'])
    time.sleep(.5)
    assert read() == done and not done['interrupted']
    out = {'passed': True, 'active_heartbeat_preserved': True, 'active_task_completed': True,
        'stopped_resume_passed': True, 'inflight_resume_passed': True,
        'jdk_image': image, 'final': done, 'production_mutations': False}
    (p / 'linux-test.json').write_text(json.dumps(out, indent=2))
    print(json.dumps(out))
finally:
    subprocess.run(['docker', 'rm', '-f', cid], check=True, stdout=subprocess.DEVNULL)
