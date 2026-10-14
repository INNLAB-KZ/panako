import os, pathlib, json, subprocess, time, datetime, copy, urllib.request, yaml
from redis_state import Redis, env, pairs

os.umask(0o077)
p = pathlib.Path('/home/bekzat/refine-lease-wave-b-20261009')
target = pathlib.Path('/home/bekzat/project/docker-compose.yml')
image = 'local/ozen-panako:refine-lease-20261007-60a75e63'
def run(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE)
def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]
def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save():
    tmp = p/'status.tmp'; tmp.write_text(json.dumps(d, indent=2)); tmp.replace(p/'status.json')

assert not (p/'status.json').exists()
assert target.read_bytes() == (p/'before.yml').read_bytes()
cs = json.loads(run(['docker', 'inspect', *run(['docker', 'ps', '-q']).split()]))
old = [c for c in cs if c['Config']['Labels'].get('com.docker.compose.project') == 'project' and c['Config']['Labels'].get('com.docker.compose.service') == 'panako-api']
assert len(old) == 5 and len(cs) == 6
others = [c for c in cs if c not in old]
assert all(c['Image'] == 'sha256:c8f3eade41b294ad0c12cad6b81b17197139e6244643e01c3dd0d14c710fedd3' and not c['Mounts'] for c in old)
e = env(old[0]); assert all(env(c) == e for c in old)
assert e['CLICKHOUSE_HOST'] == '10.0.0.10' and e['MONITOR_PARALLEL_WINDOWS'] == '2' and e['REDIS_WORKER_THREADS'] == '1'
mem = {x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
assert mem['MemAvailable'] > 8*1024**2 and mem['MemTotal'] > 30*1024**2
inspect(image)
newenv = dict(e, JAVA_TOOL_OPTIONS='-Xmx3584m', KAFKA_ENABLED='FALSE')
base = ['docker', 'compose', '-p', 'project', '--project-directory', str(target.parent)]
override=target.parent/'docker-compose.audio-origin-canary.yml'
override_bytes=override.read_bytes();(p/'override-before.yml').write_bytes(override_bytes)
override_doc=yaml.safe_load(override_bytes);override_doc['services']['panako-api']['image']=image
new_override=p/'override-proposed.yml';new_override.write_text(yaml.safe_dump(override_doc,sort_keys=False))
before = json.loads(run(base+['-f', str(target), '-f', str(override), 'config', '--format', 'json']))
doc = yaml.safe_load(target.read_text()); s = doc['services']['panako-api']
assert s['deploy']['replicas'] == 5
s.update(image=image, environment={k:v.replace('$','$$') for k,v in newenv.items()}, mem_limit='4608m', cpus=3)
proposal = p/'proposed.yml'; proposal.write_text(yaml.safe_dump(doc, sort_keys=False))
after = json.loads(run(base+['-f',str(proposal),'-f',str(new_override),'config','--format','json']))
assert after['services']['panako-api']['environment'] == newenv
a=copy.deepcopy(before); b=copy.deepcopy(after)
for cfg in (a,b):
    for key in ('environment','image','mem_limit','cpus'):cfg['services']['panako-api'].pop(key,None)
assert a==b
(p/'live-before.json').write_text(json.dumps(cs))
r=Redis(e); stream='panako.monitor.refine.request'; group='panako-worker-refine'
def pending(c):
    rows=[]
    for raw in r.cmd('XINFO','CONSUMERS',stream,group):
        owner=pairs(raw)['name']
        if owner.startswith(c['Config']['Hostname']+'-'):
            rows.extend(r.cmd('XPENDING',stream,group,'-','+',1000,owner))
    return rows
d={'phase':'draining','started_at':utc(),'old_names':[c['Name'] for c in old]}; save()
frozen=set()
try:
    deadline=time.monotonic()+180
    while time.monotonic()<deadline and len(frozen)<len(old):
        for c in old:
            if c['Id'] in frozen:continue
            if not pending(c):
                run(['docker','pause',c['Id']]);time.sleep(2)
                if not pending(c):frozen.add(c['Id'])
                else:run(['docker','unpause',c['Id']])
        time.sleep(2)
    d['pending_before_stop']=[row for c in old for row in pending(c)];d['idle_drained']=len(frozen);save()
    assert target.read_bytes()==(p/'before.yml').read_bytes() and override.read_bytes()==override_bytes
    run(['docker','update','--restart=no',*[c['Id'] for c in old]])
    for cid in list(frozen):run(['docker','kill',cid]);frozen.remove(cid)
    running=[c['Id'] for c in old if inspect(c['Id'])['State']['Running']]
    if running:run(['docker','stop','--timeout','30',*running])
    d['source_stopped_at']=utc();d['pending_after_stop']=[row for c in old for row in pending(c)];d['phase']='recreating';save()
    tmp=target.with_name('docker-compose.wave1.tmp');tmp.write_bytes(proposal.read_bytes());os.chmod(tmp,0o600);os.replace(tmp,target)
    override_tmp=override.with_name(override.name+'.wave.tmp');override_tmp.write_bytes(new_override.read_bytes());os.replace(override_tmp,override)
    run(base+['-f',str(target),'-f',str(override),'up','-d','--no-deps','--pull','never','--force-recreate','--scale','panako-api=5','panako-api'])
    ids=run(['docker','ps','-q','--filter','label=com.docker.compose.project=project','--filter','label=com.docker.compose.service=panako-api']).split()
    assert len(ids)==5
    new=[inspect(cid) for cid in ids]
    for c in new:
        assert env(c)==newenv and c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==3000000000
        assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
    d['new']=[{'id':c['Id'],'name':c['Name'],'port':8080,'host':next(v['IPAddress'] for v in c['NetworkSettings']['Networks'].values() if v['IPAddress'])} for c in new];save()
    for item in d['new']:
        healthy=False
        for _ in range(60):
            try:
                with urllib.request.urlopen('http://'+item.get('host','127.0.0.1')+':'+str(item['port'])+'/api/v1/health',timeout=2) as response:healthy=json.load(response).get('status')=='ok'
            except Exception:pass
            if healthy:break
            time.sleep(2)
        assert healthy
    assert all(inspect(c['Id'])['State']['Running'] for c in others)
    d.update(phase='running',healthy_at=utc());save()
    log=open(p/'watch.log','a')
    proc=subprocess.Popen(['python3',str(p/'watch.py')],stdout=log,stderr=log,start_new_session=True)
    d['watch_pid']=proc.pid
    task_log=open(p/'tasks.log','a')
    tasks_proc=subprocess.Popen(['python3',str(p/'watch-wave-tasks.py'),str(p)],stdout=task_log,stderr=task_log,start_new_session=True)
    d['tasks_pid']=tasks_proc.pid;save()
    print(json.dumps({'phase':d['phase'],'count':len(new),'interrupted_pending':len(d['pending_after_stop']),'watch_pid':proc.pid}))
except BaseException as ex:
    for cid in frozen:subprocess.run(['docker','unpause',cid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    d.update(phase='failed',error=type(ex).__name__);save()
    # Restore the backed-up service configuration; Compose recreates removed sources.
    target.write_bytes((p/'before.yml').read_bytes())
    override_tmp=override.with_name(override.name+'.rollback.tmp');override_tmp.write_bytes(override_bytes);os.replace(override_tmp,override)
    result=subprocess.run(base+['-f',str(target),'-f',str(override),'up','-d','--no-deps','--pull','never','--scale','panako-api=5','panako-api'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (p/'rollback.log').write_bytes(result.stdout+result.stderr)
    d['rollback_exit']=result.returncode;save()
    raise
