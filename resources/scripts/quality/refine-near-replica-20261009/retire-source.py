import pathlib,subprocess,json,os,time,datetime,copy,yaml
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path('/home/bekzat/refine-near-replica-20261009');p.mkdir(mode=0o700,exist_ok=True)
target=pathlib.Path('/home/bekzat/project/docker-compose.yml')
def run(a):return subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save():
    t=p/'status.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'status.json')
assert not (p/'status.json').exists()
cs=json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]))
sources=[c for c in cs if c['Config']['Labels'].get('com.docker.compose.project')=='project' and c['Config']['Labels'].get('com.docker.compose.service')=='panako-api']
assert len(sources)==5 and len(cs)==6 and all(c['RestartCount']==0 for c in sources)
before=target.read_bytes();(p/'before.yml').write_bytes(before);(p/'before.inspect.json').write_text(json.dumps(cs))
doc=yaml.safe_load(before);assert doc['services']['panako-api']['deploy']['replicas']==5
doc['services']['panako-api']['deploy']['replicas']=4
proposal=p/'proposed.yml';proposal.write_text(yaml.safe_dump(doc,sort_keys=False))
base=['docker','compose','-p','project','--project-directory',str(target.parent)]
a=json.loads(run(base+['-f',str(target),'config','--format','json']));b=json.loads(run(base+['-f',str(proposal),'config','--format','json']));want=copy.deepcopy(a);want['services']['panako-api']['deploy']['replicas']=4;assert b==want
r=Redis(env(sources[0]));stream='panako.monitor.refine.request';group='panako-worker-refine'
owners=[pairs(v)['name'] for v in r.cmd('XINFO','CONSUMERS',stream,group)]
def pending(c):
    return [row for owner in owners if owner.startswith(c['Config']['Hostname']+'-') for row in r.cmd('XPENDING',stream,group,'-','+',1000,owner)]
d={'phase':'waiting_for_idle','started_at':utc(),'replacement_host':'91.99.52.184','replacement_container':'refine-r1-move-20261007-refine-from-main-1'};save();frozen=None;selected=None;changed=False
try:
    deadline=time.monotonic()+600
    while time.monotonic()<deadline and selected is None:
        for c in sources:
            if pending(c):continue
            run(['docker','pause',c['Id']]);frozen=c;time.sleep(1)
            if not pending(c):selected=c;break
            run(['docker','unpause',c['Id']]);frozen=None
        if selected is None:time.sleep(0.5)
    if selected is None:
        d.update(phase='idle_not_reached',finished_at=utc());save();raise SystemExit(0)
    assert target.read_bytes()==before
    d.update(source_name=selected['Name'],source_id=selected['Id'],pending_before_stop=pending(selected));assert not d['pending_before_stop'];save()
    run(['docker','update','--restart=no',selected['Id']])
    run(['docker','kill',selected['Id']]);frozen=None;changed=True
    tmp=target.with_name('docker-compose.retire.tmp');tmp.write_bytes(proposal.read_bytes());os.chmod(tmp,0o600);os.replace(tmp,target)
    assert not inspect(selected['Id'])['State']['Running'] and not pending(selected)
    assert all(inspect(c['Id'])['State']['Running'] for c in cs if c['Id']!=selected['Id'])
    d.update(phase='complete',finished_at=utc(),remaining_refine=5,pending_after_stop=pending(selected),unrelated_unchanged=True);save();print(json.dumps(d))
except BaseException as ex:
    if isinstance(ex,SystemExit):raise
    if frozen:subprocess.run(['docker','unpause',frozen['Id']],stdout=subprocess.DEVNULL)
    if selected:
        target.write_bytes(before)
        run(['docker','update','--restart='+selected['HostConfig']['RestartPolicy']['Name'],selected['Id']])
        if changed:run(['docker','start',selected['Id']])
    d.update(phase='failed',error=type(ex).__name__);save();raise
