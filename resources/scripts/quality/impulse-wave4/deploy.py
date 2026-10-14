import pathlib,json,subprocess,time,os,datetime,urllib.request
from redis_state import env,Redis,state
os.umask(0o077)
p=pathlib.Path('/home/bekzat/impulse-wave4-20260930');service='panako-api';image='local/ozen-panako:impulse-fix-20260929-83fdabf6'
def run(args):return subprocess.check_output(args,text=True)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def containers():return json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]))
allc=containers();old=[c for c in allc if c['Config']['Labels'].get('com.docker.compose.service')==service];assert len(old)==6
assert all(c['Config']['Image']=='local/ozen-panako:input-pilot-20260925-5d50209b' and c['State']['Running'] and not c['State']['Paused'] for c in old)
files=old[0]['Config']['Labels']['com.docker.compose.project.config_files'].split(',')
base=['docker','compose','--project-name','project']
for f in files:base+=['-f',f]
override=pathlib.Path('/home/bekzat/project/docker-compose.impulse-wave4-20260930.yml');assert not override.exists()
proposal=json.dumps({'services':{service:{'image':image}}});(p/'override.proposed.yml').write_text(proposal)
a=json.loads(run(base+['config','--format','json']));b=json.loads(run(base+['-f',str(p/'override.proposed.yml'),'config','--format','json']))
assert b['services'][service]['image']==image
b['services'][service]['image']=a['services'][service]['image'];assert a==b
assert all(env(c)==env(old[0]) for c in old)
image_config=json.loads(run(['docker','image','inspect',image]))[0]
effective=dict(item.split('=',1) for item in image_config['Config']['Env'])
effective.update(a['services'][service]['environment'])
assert effective==env(old[0])
(p/'before.inspect.json').write_text(json.dumps(allc));(p/'settings.json').write_text(json.dumps({'count':6,'base_files':files}))
other={c['Name']:c['Id'] for c in allc if c not in old};frozen=set();stopped=set();recreating=False
d={'phase':'draining','started_at':utc(),'old_containers':[{'name':c['Name'],'id':c['Id']} for c in old]}
def save():
 t=p/'deployment.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'deployment.json')
save()
try:
 deadline=time.monotonic()+1800
 while len(frozen)<len(old):
  if time.monotonic()>deadline:raise RuntimeError('Drain timeout')
  for c in old:
   n=c['Name']
   if n in frozen:continue
   e=env(c);r=Redis(e)
   if state(c,r,e)['pending']:continue
   assert inspect(n)['Id']==c['Id']
   run(['docker','pause',n]);frozen.add(n);time.sleep(6)
   if state(c,r,e)['pending']:run(['docker','unpause',n]);frozen.remove(n)
  if len(frozen)<len(old):time.sleep(3)
 for c in old:
  assert not state(c,Redis(env(c)),env(c))['pending']
 for c in old:
  n=c['Name'];run(['docker','update','--restart=no',n]);run(['docker','kill',n]);stopped.add(n);frozen.discard(n)
 for c in old:assert not state(c,Redis(env(c)),env(c))['pending']
 override.write_text(proposal);recreating=True;d['phase']='recreating';save()
 subprocess.run(base+['-f',str(override),'up','-d','--no-deps','--force-recreate','--scale','panako-api=6',service],check=True)
 new=[c for c in containers() if c['Config']['Labels'].get('com.docker.compose.service')==service];assert len(new)==6
 for c in new:
  assert c['Config']['Image']==image and env(c)==env(old[0])
  for key in ['Memory','MemorySwap','NanoCpus','CpuQuota','CpuPeriod','CpusetCpus']:assert c['HostConfig'][key]==old[0]['HostConfig'][key],key
  assert c['HostConfig']['RestartPolicy']['Name']=='unless-stopped'
  for _ in range(60):
   try:
    ip=next(v['IPAddress'] for v in c['NetworkSettings']['Networks'].values() if v['IPAddress'])
    with urllib.request.urlopen('http://'+ip+':8080/api/v1/health',timeout=2) as res:
     if json.load(res)['status']=='ok':break
   except Exception:pass
   time.sleep(2)
  else:raise RuntimeError('Health failed '+c['Name'])
  assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]=='83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4'
 assert all(inspect(n)['Id']==i for n,i in other.items())
 d.update(phase='running',healthy_at=utc(),new_containers=[{'name':c['Name'].lstrip('/'),'id':c['Id']} for c in new],unrelated_containers_unchanged=True)
 d['observation_containers']=d['new_containers'];save()
except BaseException as ex:
 for n in frozen:subprocess.run(['docker','unpause',n])
 if not recreating:
  for n in stopped:
   subprocess.run(['docker','update','--restart=unless-stopped',n]);subprocess.run(['docker','start',n])
 d.update(phase='operator_review_required' if recreating else 'aborted',error=type(ex).__name__);save();raise
