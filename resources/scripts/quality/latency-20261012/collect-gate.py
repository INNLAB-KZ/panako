"""Collect attributed near-hour canary results; no production mutations."""
import datetime, json, math, os, pathlib, statistics, subprocess, sys, time

os.umask(0o077)
scripts = pathlib.Path(__file__).resolve().parent
workspace = scripts.parents[3]
evidence = workspace / 'build/latency-20261012'
watch = sys.argv[1:] == ['--watch']
assert sys.argv[1:] in ([], ['--watch'])
deployment = json.loads((evidence / 'deployment-status.json').read_text())
after = {x['role']: int(datetime.datetime.fromisoformat(x['at']).timestamp() * 1000) for x in deployment['roles']}
excluded = {x['response_id'] for x in json.loads((evidence / 'recovery-publication.json').read_text())['results']}

def ssh(host, command, body=None):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20',
                             '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=3',
                             'bekzat@' + host, command], input=body, capture_output=True, text=True, timeout=90)
    assert result.returncode == 0, 'Read-only SSH collection failed on ' + host
    return json.loads(result.stdout)

while True:
    try:
        pair = ssh('167.233.61.228', 'python3 -', (scripts / 'worker-audit.py').read_text())
        cohorts = {}
        for host in ['167.233.61.250', '167.233.105.74']:
            cohorts[host] = ssh(host, 'cat /home/bekzat/latency-fleet-20261012/observation.json')
            (evidence / ('fleet/' + host + '-canary-observation.json')).write_text(json.dumps(cohorts[host], indent=2))
        canary = {'at': min(x['at'] for x in cohorts.values()),
                  'watcher_error': any(x.get('watcher_error') for x in cohorts.values()),
                  'containers': {k: v for x in cohorts.values() for k, v in x['containers'].items()},
                  'responses': {k: v for x in cohorts.values() for k, v in x['responses'].items()},
                  'old_pending': [v for x in cohorts.values() for v in x.get('old_pending', [])]}
        (evidence / 'worker-followup.json').write_text(json.dumps(pair, indent=2))
        (evidence / 'fleet/canary-observation.json').write_text(json.dumps(canary, indent=2))
        errors = []
        samples = []
        for role, v in pair['roles'].items():
            if not v['running'] or v['restarts'] or v['oom']:
                errors.append('isolated_' + role + '_state')
            recent = [x for x in v['samples'] if int(x['id'].split('-')[0]) >= after[role] and x['id'] not in excluded]
            if any(x['status'] != 'ok' for x in recent):
                errors.append('isolated_' + role + '_response')
            if role == 'stage1':
                samples += [dict(x, cohort='isolated') for x in recent if x['status'] == 'ok'
                            and 3300 <= float(x['duration_seconds'] or 0) <= 3900]
        if canary.get('watcher_error'):
            errors.append('canary_observer')
        age = (datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromisoformat(canary['at'])).total_seconds()
        if age > 90:
            errors.append('stale_canary_observer')
        if canary['old_pending']:
            errors.append('old_consumer_pending')
        for c in canary['containers'].values():
            if not c['running'] or c['restarts'] or c['oom'] or any(c['errors'].values()):
                errors.append('canary_container')
        for x in canary['responses'].values():
            if x['status'] != 'ok':
                errors.append('canary_response')
            if x['role'] == 'stage1' and x['status'] == 'ok' and x['matched_completion'] and 3300 <= float(x['duration_seconds'] or 0) <= 3900:
                samples.append(dict(x, cohort='main_queue_canary'))
        unique = {x['recording_id']: x for x in samples}
        values = sorted(x['processing_time_ms'] for x in unique.values())
        n_canary = sum(x['cohort'] == 'main_queue_canary' for x in unique.values())
        summary = {'n': len(values), 'isolated': len(values) - n_canary, 'main_queue_canary': n_canary,
                   'mean_ms': round(statistics.mean(values), 2) if values else None,
                   'p95_ms': values[math.ceil(len(values) * .95) - 1] if values else None,
                   'max_ms': max(values, default=None)}
        completed_containers = {x['container'] for x in unique.values() if x['cohort'] == 'main_queue_canary'}
        passed = (not errors and len(values) >= 20 and n_canary >= 5
                  and len(canary['containers']) == 5 and len(completed_containers) == 5
                  and summary['p95_ms'] <= 480000 and summary['max_ms'] <= 600000)
        out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'passed': passed,
               'summary': summary, 'errors': errors, 'samples': list(unique.values())}
        tmp = evidence / 'fleet/gate.tmp'
        tmp.write_text(json.dumps(out, indent=2))
        tmp.replace(evidence / 'fleet/gate.json')
        print(json.dumps({k: out[k] for k in ['at', 'passed', 'summary', 'errors']}), flush=True)
        if passed or not watch:
            break
    except Exception as error:
        print(json.dumps({'collection_error': type(error).__name__}), flush=True)
        if not watch:
            raise
    time.sleep(45)
