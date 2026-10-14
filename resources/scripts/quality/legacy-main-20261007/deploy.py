import pathlib,json,subprocess,copy,hashlib,os,time,urllib.request,datetime
from redis_state import Redis,env,pairs
os.umask(0o077);p=pathlib.Path('/home/bekzat/legacy-main-20261007');target=pathlib.Path('/home/bekzat/project/docker-compose.yml');old=json.loads((p/'before.inspect.json').read_text());delta=json.loads((p/'changes.json').read_text());desired=json.loads((p/'desired-env.json').read_text());base=['docker','compose','--project-name','project','--project-directory',str(target.parent)];states=[]
def run(a):return subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
def inspect(name):return json.loads(run(['docker','inspect',name]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(): (p/'deployment.json').write_text(json.dumps({'at':utc(),'services':states},indent=2))
assert target.read_bytes()==(p/'before.yml').read_bytes();a=json.loads(run(base+['-f',str(target),'config','--format','json']));b=json.loads(run(base+['-f',str(p/'proposed.yml'),'config','--format','json']));want=copy.deepcopy(a)
for svc in delta:want['services'][svc]['environment']=desired[svc];want['services'][svc].pop('env_file',None)
assert want==b,'Unexpected Compose difference'
for c in old:
 assert inspect(c['Id'])['State']['Running'];im=json.loads(run(['docker','image','inspect',c['Config']['Image']]))[0];assert im['Id']==c['Image'];svc=c['Config']['Labels']['com.docker.compose.service'];live=env(c);expected=live.copy();expected.update(delta[svc]);assert expected==desired[svc],'Unexpected runtime environment change'
allc=json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]));unrelated={c['Name']:c['Id'] for c in allc if c['Id'] not in {x['Id'] for x in old}};r=Redis(env(old[1]));streams=['panako-store-requests','panako-store-patch'];group='panako-workers'
def pending(c):
 out=[]
 for stream in streams:
  for v in r.cmd('XINFO','CONSUMERS',stream,group):
   owner=pairs(v)['name']
   if owner.startswith(c['Config']['Hostname']+'-'):out+=r.cmd('XPENDING',stream,group,'-','+',100,owner)
 return out
# Freeze only idle store consumers, then verify no message arrived in flight.
frozen=[]
try:
 for c in old:
  if c['Config']['Labels']['com.docker.compose.service']!='panako-store':continue
  assert not pending(c),'Store has pending task';run(['docker','pause',c['Id']]);frozen.append(c['Id'])
 time.sleep(6);assert all(not pending(c) for c in old if c['Id'] in frozen),'Store acquired task while freezing'
 # Abort HTTP recreation if an inbound request is currently connected.
 http=next(c for c in old if c['Config']['Labels']['com.docker.compose.service']=='panako-api-http')
 evidence=json.loads((p/'http-drain-evidence.json').read_text());assert evidence['container_id']==http['Id'] and evidence['active_application_handlers']==0 and evidence['http_threads_waiting_for_request_line']==10

 st=target.stat();tmp=target.with_name(target.name+'.main.tmp');tmp.write_bytes((p/'proposed.yml').read_bytes());os.chmod(tmp,0o600);os.replace(tmp,target)
 for svc,count in [('panako-api-http',1),('panako-store',2)]:
  if svc=='panako-store':
   for c in old:
    if c['Id'] in frozen:run(['docker','kill',c['Id']]);frozen.remove(c['Id'])
  run(base+['-f',str(target),'up','-d','--no-deps','--force-recreate','--pull','never','--scale',svc+'='+str(count),svc])
  cs=[inspect(c['Name']) for c in old if c['Config']['Labels']['com.docker.compose.service']==svc];results=[]
  for c in cs:
   previous=next(x for x in old if x['Name']==c['Name']);expected=env(previous);expected.update(delta[svc]);assert env(c)==expected and c['Image']==previous['Image'];assert c['Config']['Cmd']==previous['Config']['Cmd'];assert c['Mounts']==previous['Mounts']
   for k in ['Memory','MemorySwap','NanoCpus','CpuQuota','CpuPeriod','RestartPolicy','PortBindings']:assert c['HostConfig'][k]==previous['HostConfig'][k],k
   ip=next(n['IPAddress'] for n in c['NetworkSettings']['Networks'].values() if n['IPAddress']);root='http://'+ip+':8080';health=False
   for _ in range(60):
    try:
     with urllib.request.urlopen(root+'/api/v1/health',timeout=2) as resp:health=json.load(resp).get('status')=='ok'
    except Exception:pass
    if health:break
    time.sleep(2)
   assert health,'Health failed'
   with urllib.request.urlopen(urllib.request.Request(root+'/api/v1/stats',headers={'X-API-Key':expected.get('API_KEY','')}),timeout=60) as resp:stats=json.load(resp)
   results.append({'name':c['Name'],'id':c['Id'],'health':health,'stats':stats,'pending':pending(c) if svc=='panako-store' else []})
  states.append({'service':svc,'phase':'running','at':utc(),'containers':results});save()
 assert all(inspect(n)['Id']==i and inspect(n)['State']['Running'] for n,i in unrelated.items());(p/'complete.json').write_text(json.dumps({'at':utc(),'other_containers_unchanged':True}));print(json.dumps({'services':states,'other_containers_unchanged':True}))
except BaseException:
 for identifier in frozen:subprocess.run(['docker','unpause',identifier],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 raise
