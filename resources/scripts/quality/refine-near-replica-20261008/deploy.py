import os,pathlib,json,subprocess,time,datetime,urllib.request,copy
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path('/home/bekzat/refine-near-replica-20261008')
target=pathlib.Path('/home/bekzat/refine-r1-move-20261007/compose.json')
name='refine-r1-move-20261007-refine-from-main-1'
def run(args):return subprocess.check_output(args,text=True,stderr=subprocess.PIPE)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save():
    t=p/'status.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'status.json')
assert not (p/'status.json').exists()
old=inspect(name);assert old['State']['Running'] and old['RestartCount']==0
source=json.loads((p/'source-env.json').read_text());oldenv=env(old)
assert {k for k in source.keys()|oldenv.keys() if source.get(k)!=oldenv.get(k)}=={'CLICKHOUSE_HOST','OLAF_CLICKHOUSE_URL'}
assert source['CLICKHOUSE_HOST']=='10.0.0.12' and source['MONITOR_PARALLEL_WINDOWS']=='2' and source['JAVA_TOOL_OPTIONS']=='-Xmx3584m'
assert run(['docker','exec',name,'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
mem={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()};assert mem['MemAvailable']>6*1024**2
with urllib.request.urlopen('http://10.0.0.12:8123/ping',timeout=5) as res:assert res.read().strip()==b'Ok.'
before=target.read_bytes();(p/'before.json').write_bytes(before);(p/'before.inspect.json').write_text(json.dumps(old))
others=[inspect(x) for x in run(['docker','ps','-q']).split()]
others=[x for x in others if x['Id']!=old['Id']]
doc=json.loads(before);s=doc['services']['refine-from-main']
newenv=dict(source,JAVA_TOOL_OPTIONS='-Xmx3584m -Xlog:gc')
s['environment']={k:v.replace('$','$$') for k,v in newenv.items()}
proposal=p/'proposed.json';proposal.write_text(json.dumps(doc,indent=2))
base=['docker','compose','-p','refine-r1-move-20261007','--project-directory',str(target.parent)]
a=json.loads(run(base+['-f',str(target),'config','--format','json']));b=json.loads(run(base+['-f',str(proposal),'config','--format','json']))
expected=copy.deepcopy(a);expected['services']['refine-from-main']['environment']=newenv;assert b==expected
r=Redis(oldenv)
def pending():
    rows=[]
    for v in r.cmd('XINFO','CONSUMERS','panako.monitor.refine.request','panako-worker-refine'):
        owner=pairs(v)['name']
        if owner.startswith(old['Config']['Hostname']+'-'):rows.extend(r.cmd('XPENDING','panako.monitor.refine.request','panako-worker-refine','-','+',1000,owner))
    return rows
d={'phase':'draining','started_at':utc(),'old_id':old['Id'],'old_route':'10.0.1.2','new_route':'10.0.0.12'};save();frozen=False
try:
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        if not pending():
            run(['docker','pause',old['Id']]);frozen=True;time.sleep(2)
            if not pending():break
            run(['docker','unpause',old['Id']]);frozen=False
        time.sleep(2)
    d['pending_before_stop']=pending();save();assert target.read_bytes()==before
    if frozen:run(['docker','kill',old['Id']]);frozen=False
    else:run(['docker','stop','--timeout','30',old['Id']])
    d.update(source_stopped_at=utc(),pending_after_stop=pending(),phase='recreating');save()
    tmp=target.with_suffix('.tmp');tmp.write_bytes(proposal.read_bytes());os.replace(tmp,target)
    run(base+['-f',str(target),'up','-d','--no-deps','--pull','never','refine-from-main'])
    c=inspect(name);assert c['Id']!=old['Id'] and env(c)==newenv
    assert c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==3000000000
    assert run(['docker','exec',name,'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
    healthy=False
    for _ in range(60):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8093/api/v1/health',timeout=2) as res:healthy=json.load(res).get('status')=='ok'
        except Exception:pass
        if healthy:break
        time.sleep(2)
    assert healthy and all(inspect(x['Id'])['State']['Running'] for x in others)
    d.update(phase='running',healthy_at=utc(),new=[{'id':c['Id'],'name':name,'port':8093}]);save()
    with open(p/'watch.log','a') as log:proc=subprocess.Popen(['python3',str(p/'watch.py')],stdout=log,stderr=log,start_new_session=True)
    d['watch_pid']=proc.pid;save();print(json.dumps({'phase':d['phase'],'pending':len(d['pending_after_stop']),'watch_pid':proc.pid}))
except BaseException as ex:
    if frozen:subprocess.run(['docker','unpause',old['Id']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    target.write_bytes(before)
    result=subprocess.run(base+['-f',str(target),'up','-d','--no-deps','--pull','never','refine-from-main'],capture_output=True)
    (p/'rollback.log').write_bytes(result.stdout+result.stderr)
    d.update(phase='failed',error=type(ex).__name__,rollback_exit=result.returncode);save();raise
