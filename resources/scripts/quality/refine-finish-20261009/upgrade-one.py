"""Replace an intermediate-build refine in its existing Compose project."""
import copy,datetime,json,os,pathlib,subprocess,sys,time,urllib.request,yaml
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path(sys.argv[1]);name=sys.argv[2]
image='local/ozen-panako:refine-jdbc-20261004-60a75e63'
def run(args):return subprocess.check_output(args,text=True,stderr=subprocess.PIPE)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save():
    t=p/'status.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'status.json')
assert not (p/'status.json').exists()
old=inspect(name);assert old['State']['Running'] and old['RestartCount']==0
assert run(['docker','exec',name,'sha256sum','/app/panako.jar']).split()[0]=='5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022'
inspect(image);labels=old['Config']['Labels'];files=labels['com.docker.compose.project.config_files'].split(',');assert files
target=pathlib.Path(files[0]);project=labels['com.docker.compose.project'];service=labels['com.docker.compose.service']
overrides=[pathlib.Path(f) for f in files[1:]];override_bytes={str(f):f.read_bytes() for f in overrides}
for index,f in enumerate(overrides):
    (p/('override-'+str(index)+'.yml')).write_bytes(override_bytes[str(f)])
    assert 'image' not in yaml.safe_load(override_bytes[str(f)])['services'].get(service,{})
override_args=[arg for f in overrides for arg in ['-f',str(f)]]
before=target.read_bytes();(p/'before.yml').write_bytes(before);(p/'source.json').write_text(json.dumps(old))
e=env(old);assert e['MONITOR_PARALLEL_WINDOWS']=='2' and e['CLICKHOUSE_HOST']=='10.0.1.2'
assert old['HostConfig']['Memory']==4608*1024**2 and not old['Mounts']
others=[inspect(cid) for cid in run(['docker','ps','-q']).split()];others=[c for c in others if c['Id']!=old['Id']]
base=['docker','compose','-p',project,'--project-directory',str(target.parent)]
doc=yaml.safe_load(before);doc['services'][service].update(image=image,environment={k:v.replace('$','$$') for k,v in e.items()})
doc['services'][service].pop('env_file',None)
proposal=p/'proposed.yml';proposal.write_text(yaml.safe_dump(doc,sort_keys=False))
a=json.loads(run(base+['-f',str(target)]+override_args+['config','--format','json']));b=json.loads(run(base+['-f',str(proposal)]+override_args+['config','--format','json']))
expected=copy.deepcopy(a);expected['services'][service].update(image=image,environment=e);assert b==expected
args=(old['Config']['Cmd'] or []);port=next(int(x.split('=')[1]) for x in args if x.startswith('SERVER_PORT='))
assert old['HostConfig']['NetworkMode']=='host'
r=Redis(e)
def pending():
    rows=[]
    for raw in r.cmd('XINFO','CONSUMERS','panako.monitor.refine.request','panako-worker-refine'):
        owner=pairs(raw)['name']
        if owner.startswith(old['Config']['Hostname']+'-'):rows.extend(r.cmd('XPENDING','panako.monitor.refine.request','panako-worker-refine','-','+',1000,owner))
    return rows
d={'phase':'draining','started_at':utc(),'source':name,'source_id':old['Id']};save();frozen=False
try:
    end=time.monotonic()+180
    while time.monotonic()<end:
        if not pending():
            run(['docker','pause',old['Id']]);frozen=True;time.sleep(2)
            if not pending():break
            run(['docker','unpause',old['Id']]);frozen=False
        time.sleep(1)
    d['pending_before_stop']=pending();save();assert target.read_bytes()==before and all(f.read_bytes()==override_bytes[str(f)] for f in overrides)
    if frozen:run(['docker','kill',old['Id']]);frozen=False
    else:run(['docker','stop','--timeout','30',old['Id']])
    d.update(source_stopped_at=utc(),pending_after_stop=pending());save()
    tmp=target.with_suffix('.upgrade.tmp');tmp.write_bytes(proposal.read_bytes());os.replace(tmp,target)
    run(base+['-f',str(target)]+override_args+['up','-d','--no-deps','--pull','never',service])
    c=inspect(name);assert c['Id']!=old['Id'] and env(c)==e
    assert c['HostConfig']['Memory']==old['HostConfig']['Memory'] and c['HostConfig']['NanoCpus']==old['HostConfig']['NanoCpus']
    assert run(['docker','exec',name,'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
    healthy=False
    for _ in range(60):
        try:
            with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/api/v1/health',timeout=2) as resp:healthy=json.load(resp).get('status')=='ok'
        except Exception:pass
        if healthy:break
        time.sleep(2)
    assert healthy and all(inspect(x['Id'])['State']['Running'] for x in others)
    d.update(phase='running',healthy_at=utc(),new=[{'id':c['Id'],'name':name,'port':port}]);save()
    for script,args in [('watch.py',[]),('watch-wave-tasks.py',[str(p)])]:
        with open(p/(script+'.log'),'a') as log:proc=subprocess.Popen(['python3',str(p/script),*args],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
        d[script+'_pid']=proc.pid
    save();print(json.dumps({'phase':d['phase'],'name':name,'pending':len(d['pending_after_stop'])}))
except BaseException as ex:
    if frozen:subprocess.run(['docker','unpause',old['Id']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    target.write_bytes(before)
    result=subprocess.run(base+['-f',str(target)]+override_args+['up','-d','--no-deps','--pull','never',service],capture_output=True)
    (p/'rollback.log').write_bytes(result.stdout+result.stderr);d.update(phase='failed',error=type(ex).__name__,rollback_exit=result.returncode);save();raise
