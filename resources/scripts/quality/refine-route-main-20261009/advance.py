"""Promote a socket pilot only after observed full-hour success and stability."""
import datetime, json, pathlib, subprocess, sys, time, urllib.request

p = pathlib.Path(sys.argv[1])
pilot = pathlib.Path('/home/bekzat/refine-socket-canary-20261009')
count = sys.argv[2]
old_host = sys.argv[3]
deadline = time.monotonic() + 2400
assert not (p/'advance.json').exists() and not (p/'status.json').exists()

def save(d):
    d['at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    tmp=p/'advance.tmp'; tmp.write_text(json.dumps(d,indent=2)); tmp.replace(p/'advance.json')

while time.monotonic() < deadline:
    w = json.loads((pilot/'watch.json').read_text())
    t = json.loads((pilot/'tasks.json').read_text())
    full = [x for x in t['responses'] if x.get('matched_own_completion') and
            x.get('status') == 'ok' and (x.get('segment_seconds') or 0) >= 3300 and
            3300 <= (x.get('duration_seconds') or 0) <= 3900]
    failed = [x for x in t['responses'] if x.get('status') != 'ok']
    fresh = (datetime.datetime.now(datetime.timezone.utc) -
             datetime.datetime.fromisoformat(w['at'])).total_seconds() < 90
    review_file = pilot/'reviewed-warnings.json'
    reviewed = json.loads(review_file.read_text()).get('allowed_counts', {}) if review_file.exists() else {}
    def healthy_row(x):
        allowed = reviewed.get(x.get('name'), {})
        return (x.get('health', False) and not x.get('restarts') and not x.get('oom') and
                all(value <= allowed.get(key, 0) for key,value in x.get('errors', {}).items()))
    healthy = all(healthy_row(x) for x in w['alerts'] + w['containers'])
    # The finite 30-minute observer can finish before the second full-hour
    # workload arrives. Refresh health against the same live containers while
    # the independent 40-minute task attribution observer is still current.
    if w.get('finished') and not fresh:
        task_age = (datetime.datetime.now(datetime.timezone.utc) -
                    datetime.datetime.fromisoformat(t['at'])).total_seconds()
        if task_age < 90:
            spec = json.loads((pilot/'status.json').read_text())
            fresh = True
            for item in spec['new']:
                c = json.loads(subprocess.check_output(['docker','inspect',item['id']]))[0]
                logs = subprocess.check_output(['docker','logs','--since',spec['healthy_at'],
                         '--tail','50000',item['id']],stderr=subprocess.STDOUT,text=True)
                with urllib.request.urlopen('http://127.0.0.1:'+str(item['port'])+'/api/v1/health',timeout=3) as res:
                    ok = json.load(res).get('status') == 'ok'
                row = {'name':item['name'], 'health':c['State']['Running'] and ok,
                       'restarts':c['RestartCount'], 'oom':c['State']['OOMKilled'],
                       'errors':{key:logs.count(key) for key in w['containers'][0]['errors']}}
                healthy = healthy and healthy_row(row)
            mem = {x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
            healthy = healthy and mem['MemAvailable'] > 6*1024**2
    d = {'phase':'waiting_for_pilot', 'pilot_elapsed_seconds':w['elapsed_seconds'],
         'full_hour_processing_ms':[x['processing_time_ms'] for x in full],
         'healthy':healthy, 'fresh':fresh, 'failed_responses':len(failed)}
    save(d)
    if not healthy or failed:
        d['phase']='blocked_pilot_error'; save(d); sys.exit(2)
    if fresh and w['elapsed_seconds'] >= 900 and len(full) >= 2 and w['all_recovered']:
        times=d['full_hour_processing_ms']
        if max(times)>600000 or sum(times)/len(times)>480000:
            d['phase']='blocked_pilot_sla'; save(d); sys.exit(3)
        d['phase']='deploying'; save(d)
        with (p/'deploy.log').open('a') as log:
            result=subprocess.run(['python3',str(p/'deploy.py'),str(p),count,old_host],stdout=log,stderr=log)
        d.update(phase='observing_wave' if result.returncode==0 else 'blocked_deployment',deployment_exit=result.returncode); save(d)
        sys.exit(result.returncode)
    time.sleep(15)
d['phase']='blocked_insufficient_evidence'; save(d)
