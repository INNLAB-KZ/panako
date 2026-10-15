import pathlib,json,subprocess,time,os,datetime,urllib.request,urllib.parse,sys
from redis_state import env,Redis,state
os.umask(0o077);p=pathlib.Path('/home/bekzat/jdbc-rollout-20261004');service=sys.argv[1];count=int(sys.argv[2]);image='local/ozen-panako:jdbc-timeout-20261002-60a75e63'
def run(a):return subprocess.check_output(a,text=True)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def containers():return json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]))
allc=containers();old=[c for c in allc if c['Config']['Labels'].get('com.docker.compose.service')==service and c['Config']['Image']=='local/ozen-panako:impulse-fix-20260929-83fdabf6'];assert len(old)==count
assert all(c['State']['Running'] and not c['State']['Paused'] and env(c)==env(old[0]) for c in old)
e=env(old[0]);q=urllib.parse.parse_qs(urllib.parse.urlsplit(e['OLAF_CLICKHOUSE_URL'][5:]).query);assert not any(k in q for k in ['socket_timeout','connection_timeout','connection_request_timeout'])
l=old[0]['Config']['Labels'];files=l['com.docker.compose.project.config_files'].split(',');base=['docker','compose','--project-name',l['com.docker.compose.project']]
for f in files:base+=['-f',f]
override=pathlib.Path(files[-1]).parent/'docker-compose.jdbc-rollout-20261004.yml';assert not override.exists()
proposal=json.dumps({'services':{service:{'image':image}}});(p/'proposed.yml').write_text(proposal)
a=json.loads(run(base+['config','--format','json']));b=json.loads(run(base+['-f',str(p/'proposed.yml'),'config','--format','json']));assert b['services'][service]['image']==image;b['services'][service]['image']=a['services'][service]['image'];assert a==b
im=json.loads(run(['docker','image','inspect',image]))[0];effective=dict(x.split('=',1) for x in im['Config']['Env']);effective.update(a['services'][service]['environment']);assert effective==e
(p/'before.inspect.json').write_text(json.dumps(allc));others={c['Name']:c['Id'] for c in allc if c not in old};frozen=set();stopped=set();recreating=False
r=Redis(e);d={'phase':'draining','started_at':utc(),'old_containers':[{'name':c['Name'],'id':c['Id']} for c in old]}
def save():
 t=p/'deployment.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'deployment.json')
save()
try:
 deadline=time.monotonic()+600
 while len(frozen)<count and time.monotonic()<deadline:
  for c in old:
   n=c['Name']
   if n in frozen:continue
   if state(c,r,e)['pending']:continue
   assert inspect(n)['Id']==c['Id'];run(['docker','pause',n]);frozen.add(n);time.sleep(6)
   if state(c,r,e)['pending']:run(['docker','unpause',n]);frozen.remove(n)
  if len(frozen)<count:time.sleep(2)
 d['pending_before_stop']={c['Name']:state(c,r,e)['pending'] for c in old};save()
 for c in old:
  n=c['Name'];run(['docker','update','--restart=no',n])
  if n in frozen:run(['docker','kill',n]);frozen.remove(n)
  else:run(['docker','stop','--timeout','30',n])
  stopped.add(n)
 d['pending_after_stop']={c['Name']:state(c,r,e)['pending'] for c in old};save()
 override.write_text(proposal);recreating=True;d['phase']='recreating';save()
 subprocess.run(base+['-f',str(override),'up','-d','--no-deps','--force-recreate','--scale',service+'='+str(count),service],check=True)
 new=[c for c in containers() if c['Config']['Labels'].get('com.docker.compose.service')==service];assert len(new)==count
 for c in new:
  assert c['Config']['Image']==image and env(c)==e
  for k in ['Memory','MemorySwap','NanoCpus','CpuQuota','CpuPeriod','CpusetCpus','RestartPolicy']:assert c['HostConfig'][k]==old[0]['HostConfig'][k],k
  for _ in range(60):
   try:
    ip=next(v['IPAddress'] for v in c['NetworkSettings']['Networks'].values() if v['IPAddress'])
    with urllib.request.urlopen('http://'+ip+':8080/api/v1/health',timeout=2) as res:
     if json.load(res)['status']=='ok':break
   except Exception:pass
   time.sleep(2)
  else:raise RuntimeError('Health failed')
  assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
 assert all(inspect(n)['Id']==i for n,i in others.items())
 d.update(phase='running',healthy_at=utc(),new_containers=[{'name':c['Name'].lstrip('/'),'id':c['Id']} for c in new],unrelated_containers_unchanged=True);d['observation_containers']=d['new_containers'];save()
except BaseException as ex:
 for n in frozen:subprocess.run(['docker','unpause',n])
 if not recreating:
  for n in stopped:subprocess.run(['docker','update','--restart=unless-stopped',n]);subprocess.run(['docker','start',n])
 d.update(phase='operator_review_required' if recreating else 'aborted',error=type(ex).__name__);save();raise
