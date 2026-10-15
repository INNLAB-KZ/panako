"""Prepare tested deployment helpers on refine hosts without attaching to workers."""
import concurrent.futures, hashlib, json, os, pathlib, subprocess

os.umask(0o077)
scripts = pathlib.Path(__file__).resolve().parent
evidence = scripts.parents[3] / 'build/latency-20261012'
agent = evidence / 'drain-agent/drain-agent.jar'
expected = '7fb564fca397860a5d6decfcbfe17cda9f4a403d03e48513ed927fc1976ff4a9'
assert hashlib.sha256(agent.read_bytes()).hexdigest() == expected
assert json.loads((evidence / 'drain-agent/local-test.json').read_text())['passed']
assert json.loads((evidence / 'drain-agent/linux-test.json').read_text())['passed']
prepared = json.loads((evidence / 'fleet/preparation.json').read_text())
hosts = [h for h, d in prepared.items() if any(g.get('role') == 'refine' for g in d['groups'])]
remote = '/home/bekzat/latency-fleet-20261012'
image = 'eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda'

def run(args, body=None):
    r = subprocess.run(args, input=body, text=True, capture_output=True, timeout=300)
    assert r.returncode == 0, 'Helper preparation command failed'
    return r.stdout

def prepare(host):
    prefix = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20', 'bekzat@' + host]
    run(prefix + ['mkdir -p -m 700 ' + remote + '/drain-agent'])
    run(['scp', '-q', str(scripts / 'drain-control.py'), 'bekzat@' + host + ':' + remote + '/'])
    run(['scp', '-q', str(agent), 'bekzat@' + host + ':' + remote + '/drain-agent/drain-agent.jar'])
    body = """import hashlib,pathlib,subprocess,json
p=pathlib.Path(%r)
assert hashlib.sha256(p.read_bytes()).hexdigest()==%r
image=%r
if subprocess.run(['docker','image','inspect',image],capture_output=True).returncode:
 subprocess.run(['docker','pull','--quiet',image],check=True,stdout=subprocess.DEVNULL)
print(json.dumps({'ok':True,'agent_sha256':%r,'image_id':subprocess.check_output(['docker','image','inspect','--format','{{.Id}}',image],text=True).strip()}))
""" % (remote + '/drain-agent/drain-agent.jar', expected, image, expected)
    return host, json.loads(run(prefix + ['python3 -'], body))

out = {}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for future in concurrent.futures.as_completed([pool.submit(prepare, host) for host in hosts]):
        host, result = future.result()
        out[host] = result
        (evidence / 'fleet/drain-preparation.json').write_text(json.dumps(out, indent=2))
        print(json.dumps({'host': host, **result}), flush=True)
