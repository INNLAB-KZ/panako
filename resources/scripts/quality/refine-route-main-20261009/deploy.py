"""Move a validated shared refine service to Main; preserve recognition and limits."""
import copy,datetime,json,os,pathlib,subprocess,sys,time,urllib.request,urllib.parse,yaml
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path(sys.argv[1]);expected_count=int(sys.argv[2]);old_host=sys.argv[3]
target=pathlib.Path('/home/bekzat/project/docker-compose.yml')
def run(a):return subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save():
    t=p/'status.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'status.json')
assert not (p/'status.json').exists()
cs=json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]))
old=[c for c in cs if c['Config']['Labels'].get('com.docker.compose.project')=='project' and c['Config']['Labels'].get('com.docker.compose.service')=='panako-api']
assert len(old)==expected_count and len(cs)==expected_count+1
others=[c for c in cs if c not in old];e=env(old[0]);assert all(env(c)==e and c['RestartCount']==0 for c in old)
assert e['CLICKHOUSE_HOST']==old_host and e['OLAF_CLICKHOUSE_URL'].count(old_host)==1
assert e['MONITOR_PARALLEL_WINDOWS']=='2' and e['REDIS_WORKER_THREADS']=='1'
assert all(c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==3000000000 for c in old)
for c in old:assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
newenv=dict(e,CLICKHOUSE_HOST='10.0.1.2',OLAF_CLICKHOUSE_URL=e['OLAF_CLICKHOUSE_URL'].replace(old_host,'10.0.1.2'))
assert 'socket_rcvbuf' not in newenv['OLAF_CLICKHOUSE_URL']
newenv['OLAF_CLICKHOUSE_URL'] += ('&' if '?' in newenv['OLAF_CLICKHOUSE_URL'] else '?')+'socket_rcvbuf=0&socket_sndbuf=0'
origin=json.loads((p/'audio-origin.json').read_text())
assert set(origin)=={'AUDIO_CDN_HOST','AUDIO_ORIGIN_BASE_URL','AUDIO_ORIGIN_FALLBACK_TO_CDN'}
assert origin['AUDIO_CDN_HOST']=='cdn-broadcast.ozenx.io' and origin['AUDIO_ORIGIN_FALLBACK_TO_CDN']=='true'
assert urllib.parse.urlsplit(origin['AUDIO_ORIGIN_BASE_URL']).hostname=='hel1.your-objectstorage.com'
newenv.update(origin)
before=target.read_bytes();(p/'before.yml').write_bytes(before);(p/'before.inspect.json').write_text(json.dumps(cs))
doc=yaml.safe_load(before);assert doc['services']['panako-api']['deploy']['replicas']==expected_count
doc['services']['panako-api']['environment']={k:v.replace('$','$$') for k,v in newenv.items()}
proposal=p/'proposed.yml';proposal.write_text(yaml.safe_dump(doc,sort_keys=False))
base=['docker','compose','-p','project','--project-directory',str(target.parent)]
a=json.loads(run(base+['-f',str(target),'config','--format','json']));b=json.loads(run(base+['-f',str(proposal),'config','--format','json']))
expected=copy.deepcopy(a);expected['services']['panako-api']['environment']=newenv;assert b==expected
with urllib.request.urlopen('http://10.0.1.2:8123/ping',timeout=5) as resp:assert resp.read().strip()==b'Ok.'
r=Redis(e)
def pending(c):
    rows=[]
    for raw in r.cmd('XINFO','CONSUMERS','panako.monitor.refine.request','panako-worker-refine'):
        owner=pairs(raw)['name']
        if owner.startswith(c['Config']['Hostname']+'-'):rows.extend(r.cmd('XPENDING','panako.monitor.refine.request','panako-worker-refine','-','+',1000,owner))
    return rows
d={'phase':'draining','started_at':utc(),'old_route':old_host,'new_route':'10.0.1.2','old_names':[c['Name'] for c in old]};save();frozen=set()
try:
    end=time.monotonic()+180
    while time.monotonic()<end and len(frozen)<len(old):
        for c in old:
            if c['Id'] in frozen:continue
            if not pending(c):
                run(['docker','pause',c['Id']]);time.sleep(2)
                if not pending(c):frozen.add(c['Id'])
                else:run(['docker','unpause',c['Id']])
        time.sleep(1)
    assert target.read_bytes()==before
    d['pending_before_stop']=[row for c in old for row in pending(c)];save()
    run(['docker','update','--restart=no',*[c['Id'] for c in old]])
    for cid in list(frozen):run(['docker','kill',cid]);frozen.remove(cid)
    remaining=[c['Id'] for c in old if inspect(c['Id'])['State']['Running']]
    if remaining:run(['docker','stop','--timeout','30',*remaining])
    d.update(source_stopped_at=utc(),pending_after_stop=[row for c in old for row in pending(c)]);save()
    tmp=target.with_suffix('.route.tmp');tmp.write_bytes(proposal.read_bytes());os.replace(tmp,target)
    run(base+['-f',str(target),'up','-d','--no-deps','--pull','never','--scale','panako-api='+str(expected_count),'panako-api'])
    ids=run(['docker','ps','-q','--filter','label=com.docker.compose.project=project','--filter','label=com.docker.compose.service=panako-api']).split();assert len(ids)==expected_count
    new=[inspect(cid) for cid in ids]
    for c in new:
        assert env(c)==newenv and c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==3000000000
        assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
    d['new']=[{'id':c['Id'],'name':c['Name'],'port':int(c['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort'])} for c in new];save()
    for item in d['new']:
        healthy=False
        for _ in range(60):
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(item['port'])+'/api/v1/health',timeout=2) as resp:healthy=json.load(resp).get('status')=='ok'
            except Exception:pass
            if healthy:break
            time.sleep(2)
        assert healthy
    assert all(inspect(c['Id'])['State']['Running'] for c in others)
    d.update(phase='running',healthy_at=utc());save()
    for script in ['watch.py','watch-wave-tasks.py']:
        with open(p/(script+'.log'),'a') as log:proc=subprocess.Popen(['python3',str(p/script),str(p)],stdout=log,stderr=log,stdin=subprocess.DEVNULL,start_new_session=True)
        d[script+'_pid']=proc.pid
    save();print(json.dumps({'phase':'running','count':expected_count,'pending':len(d['pending_after_stop'])}))
except BaseException as ex:
    for cid in frozen:subprocess.run(['docker','unpause',cid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    rollback_tmp=target.with_suffix('.rollback.tmp');rollback_tmp.write_bytes(before);os.replace(rollback_tmp,target)
    result=subprocess.run(base+['-f',str(target),'up','-d','--force-recreate','--no-deps','--pull','never','--scale','panako-api='+str(expected_count),'panako-api'],capture_output=True)
    (p/'rollback.log').write_bytes(result.stdout+result.stderr);d.update(phase='failed',error=type(ex).__name__,rollback_exit=result.returncode);save();raise
