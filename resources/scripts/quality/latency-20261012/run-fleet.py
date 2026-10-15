"""Sequential fleet rollout after a measured canary gate, with per-wave observation."""
import datetime, fcntl, json, math, os, pathlib, shlex, subprocess, sys, time

os.umask(0o077)
scripts = pathlib.Path(__file__).resolve().parent
workspace = scripts.parents[3]
evidence = workspace / 'build/latency-20261012/fleet'
remote = '/home/bekzat/latency-fleet-20261012'
statepath = evidence / 'rollout.json'
assert sys.argv[1:] in ([], ['--resume'])
resume = sys.argv[1:] == ['--resume']
lock = (evidence / 'rollout.lock').open('a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
assert resume or not statepath.exists(), 'Existing rollout requires explicit resume review'
state = json.loads(statepath.read_text()) if resume else {
    'phase': 'waiting_for_canary', 'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'completed': []}

def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def save():
    state['at'] = stamp()
    tmp = statepath.with_suffix('.tmp')
    tmp.write_text(json.dumps(state, indent=2))
    tmp.replace(statepath)

def ssh(host, command, body=None, timeout=120):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20',
        '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=3', 'bekzat@' + host, command],
        input=body, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        (evidence / (host + '-rollout-command.log')).write_text(result.stdout + result.stderr)
        raise RuntimeError('Remote command failed on ' + host)
    return result.stdout

def read_json(host, path):
    for attempt in range(3):
        try:
            return json.loads(ssh(host, shlex.join(['cat', path])))
        except (RuntimeError, subprocess.TimeoutExpired):
            if attempt == 2:
                raise
            time.sleep(10)

save()
try:
    while True:
        gatefile = evidence / 'gate.json'
        if gatefile.exists():
            gate = json.loads(gatefile.read_text())
            state['canary_summary'] = gate['summary']
            save()
            if gate['passed']:
                age = (datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromisoformat(gate['at'])).total_seconds()
                assert (age < 180 or resume and state.get('current')) and not gate['errors']
                break
        time.sleep(30)
    prepared = json.loads((evidence / 'preparation.json').read_text())
    assert len(prepared) == 11 and all(v['ok'] for v in prepared.values())
    order = ['167.233.105.74', '167.233.61.228', '167.233.61.250', '167.233.53.95', '167.233.52.194',
             '46.224.159.198', '91.99.52.184', '167.233.105.218', '157.180.26.0', '62.238.0.200', '167.233.62.118']
    for host in order:
        # Refresh only the operational scripts, preserving artifacts and rollback manifests.
        result = subprocess.run(['scp', '-q', str(scripts / 'fleet-release.py'), str(scripts / 'fleet-watch.py'),
                                 'bekzat@' + host + ':' + remote + '/'], capture_output=True, timeout=120)
        assert result.returncode == 0, 'Script transfer failed'
        # Restart this task's read-only observer, preserving its atomic snapshot.
        launcher = """import os,pathlib,signal,subprocess,time
p=pathlib.Path('/home/bekzat/latency-fleet-20261012')
script=str(p/'fleet-watch.py')
for entry in pathlib.Path('/proc').iterdir():
 if not entry.name.isdigit():continue
 try:args=(entry/'cmdline').read_bytes().split(b'\\0')
 except (FileNotFoundError,PermissionError,ProcessLookupError):continue
 if script.encode() in args:
  try:os.kill(int(entry.name),signal.SIGTERM)
  except ProcessLookupError:pass
time.sleep(1)
assert not (p/'STOP-WATCH').exists()
with (p/'watch.log').open('a') as log:
 child=subprocess.Popen(['python3',script],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
(p/'watch.pid').write_text(str(child.pid))
print(child.pid)
"""
        ssh(host, 'python3 -', launcher)
        groups = prepared[host]['groups']
        for item in groups:
            if item.get('already_prepared'):
                continue  # The original additional canary is already deployed and verified by the gate.
            project, service = item['project'], item['service']
            if any(x['host'] == host and x['project'] == project and x['service'] == service for x in state['completed']):
                continue
            current = {'host': host, 'project': project, 'service': service, 'count': item['count'], 'role': item['role']}
            state.update(phase='checking_main', current=current)
            save()
            for attempt in range(20):
                main = json.loads(ssh('116.202.112.101', 'python3 -', (scripts / 'main-audit.py').read_text()))
                (evidence / 'main-latest.json').write_text(json.dumps(main, indent=2))
                h = main['host']
                ready = h['cpu_busy_pct'] < 85 and h['tx_mbps'] < 8500 and h['available_gib'] > 8 and not any(h['interface_faults'].values()) and not main['db']['8123']['errors_15min']
                state['main'] = h
                save()
                if ready:
                    break
                time.sleep(45)
            else:
                raise RuntimeError('Main headroom gate remains closed')
            state['phase'] = 'draining_and_deploying'
            save()
            statusfile = remote + '/' + project + '__' + service + '/status.json'
            launch = """import pathlib,subprocess
p=pathlib.Path(%r)
assert not (p/'status.json').exists()
with (p/'runner.log').open('a') as log:
 child=subprocess.Popen(['python3',%r,'deploy',%r,%r],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
print(child.pid)
""" % (remote + '/' + project + '__' + service, remote + '/fleet-release.py', project, service)
            existing = json.loads(ssh(host, 'python3 -',
                'import pathlib,json\np=pathlib.Path(' + repr(statusfile) + ')\nprint(p.read_text() if p.exists() else "null")\n'))
            if existing is None:
                ssh(host, 'python3 -', launch)
            else:
                assert resume and existing['phase'] in ('draining', 'recreating', 'healthy'), 'Existing deployment needs review'
            time.sleep(3)
            deadline = time.monotonic() + 2100
            while True:
                deployment = read_json(host, statusfile)
                state['deployment'] = deployment
                save()
                if deployment['phase'] == 'failed':
                    raise RuntimeError('Deployment failed; inspect automatic rollback')
                if deployment['phase'] == 'healthy':
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError('Deployment observation deadline')
                time.sleep(20)
            ids = {c['id'] for c in deployment['new']}
            required = item.get('required_successful_containers', min(3, item['count']))
            minimum_observation = item.get('minimum_observation_seconds', 120)
            assert 1 <= required <= min(3, item['count']) and minimum_observation >= 120
            state['phase'] = 'observing'
            save()
            deadline = time.monotonic() + 5400
            while True:
                observation = read_json(host, remote + '/observation.json')
                (evidence / (host + '-observation.json')).write_text(json.dumps(observation, indent=2))
                age = (datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromisoformat(observation['at'])).total_seconds()
                assert age < 90 and not observation['watcher_error'], 'Observer unhealthy'
                live = {cid: c for cid, c in observation['containers'].items() if cid in ids}
                assert not any(not c['running'] or c['restarts'] or c['oom'] or any(c['errors'].values()) for c in live.values()), 'Candidate container errors'
                responses = [x for x in observation['responses'].values() if x['container'] in ids]
                assert not any(x['status'] != 'ok' for x in responses), 'Candidate error response requires review'
                successful = [x for x in responses if x['matched_completion'] and x['status'] == 'ok']
                hour = [x for x in successful if 3300 <= float(x['duration_seconds'] or 0) <= 3900]
                if item['role'] == 'stage1':
                    assert all(x['processing_time_ms'] <= 600000 for x in hour), 'Stage-1 exceeds ten-minute target'
                    if len(hour) >= required:
                        times = sorted(x['processing_time_ms'] for x in hour)
                        assert times[math.ceil(len(times) * .95) - 1] <= 480000, 'Stage-1 p95 exceeds eight-minute gate'
                age_deploy = (datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromisoformat(deployment['healthy_at'])).total_seconds()
                samples = hour if item['role'] == 'stage1' else successful
                state['observation'] = {'successful': len(successful), 'hour_samples': len(hour),
                    'containers_with_response': len({x['container'] for x in samples}),
                    'old_pending': observation.get('old_pending', []), 'elapsed_seconds': round(age_deploy)}
                save()
                if (len(live) == len(ids) and len(samples) >= required
                        and len({x['container'] for x in samples}) >= required
                        and not observation.get('old_pending') and age_deploy >= minimum_observation):
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError('Wave observation deadline; no automatic unsafe advance')
                time.sleep(30)
            state['completed'].append({**current, 'healthy_at': deployment['healthy_at'], 'verified_at': stamp(),
                                       'responses': len(successful), 'hour_samples': len(hour),
                                       'required_successful_containers': required,
                                       'acceptance_note': item.get('acceptance_note')})
            save()
    state.update(phase='waves_complete', finished_at=stamp())
    save()
except BaseException as error:
    state.update(phase='needs_review', error=type(error).__name__ + ': ' + str(error))
    save()
    raise
