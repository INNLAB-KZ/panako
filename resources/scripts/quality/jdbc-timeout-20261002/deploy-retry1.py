import pathlib,json,subprocess,time,os,datetime,urllib.request
from redis_state import env,Redis,state
os.umask(0o077);p=pathlib.Path('/home/bekzat/jdbc-timeout-20261002-retry1');name='project-panako-api-memory-1';service='panako-api-memory';image='local/ozen-panako:jdbc-timeout-20261002-60a75e63'
def inspect(n):return json.loads(subprocess.check_output(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
c=inspect(name);assert c['Config']['Image']=='local/ozen-panako:impulse-fix-20260929-83fdabf6' and c['State']['Running'] and not c['State']['Paused']
files=c['Config']['Labels']['com.docker.compose.project.config_files'].split(',');base=['docker','compose','--project-name','project']
for f in files:base+=['-f',f]
override=pathlib.Path('/home/bekzat/project/docker-compose.jdbc-timeout-20261002.yml');assert not override.exists()
expected_env=env(c).copy()
proposal=json.dumps({'services':{service:{'image':image}}});(p/'override.proposed.yml').write_text(proposal)
old_config=json.loads(subprocess.check_output(base+['config','--format','json']))
new_config=json.loads(subprocess.check_output(base+['-f',str(p/'override.proposed.yml'),'config','--format','json']))
assert new_config['services'][service]['image']==image
new_config['services'][service]['image']=old_config['services'][service]['image'];assert old_config==new_config
(p/'settings.json').write_text(json.dumps({'count':1,'base_files':files}));(p/'before.inspect.json').write_text(json.dumps(c))
other={x['Name']:x['Id'] for x in json.loads(subprocess.check_output(['docker','inspect',*subprocess.check_output(['docker','ps','-q'],text=True).split()])) if x['Id']!=c['Id']}
e=env(c);r=Redis(e);d={'phase':'draining','started_at':utc(),'old_id':c['Id'],'prior_restarts':c['RestartCount']}
def save():
 tmp=p/'deployment.tmp';tmp.write_text(json.dumps(d,indent=2));tmp.replace(p/'deployment.json')
save();frozen=False;stopped=False;recreating=False
initial=state(c,r,e)
owners=[x['name'] for x in initial['consumer']]
assert owners and all(x.startswith(c['Config']['Hostname']+'-') for x in owners)
def busy():
 return any(r.cmd('XPENDING',initial['stream'],initial['group_id'],'-','+',1,owner) for owner in owners)

try:
 deadline=time.monotonic()+1800
 while time.monotonic()<deadline:
  if busy():time.sleep(0.05);continue
  subprocess.run(['docker','pause',name],check=True,stdout=subprocess.DEVNULL);frozen=True;time.sleep(6)
  if state(c,r,e)['pending']:
   subprocess.run(['docker','unpause',name],check=True,stdout=subprocess.DEVNULL);frozen=False;continue
  assert inspect(name)['Id']==c['Id']
  subprocess.run(['docker','update','--restart=no',name],check=True,stdout=subprocess.DEVNULL)
  subprocess.run(['docker','kill',name],check=True,stdout=subprocess.DEVNULL);frozen=False;stopped=True;break
 else:raise RuntimeError('Drain timeout')
 d['pending_after_stop']=state(c,r,e)['pending'];save()
 assert not d['pending_after_stop'], 'Task appeared during handoff'
 override.write_text(proposal);recreating=True;d['phase']='recreating';save()
 cmd=base+['-f',str(override),'up','-d','--no-deps','--force-recreate',service];subprocess.run(cmd,check=True)
 for _ in range(40):
  n=inspect(name);assert n['Config']['Image']==image and env(n)==expected_env and n['HostConfig']['Memory']==c['HostConfig']['Memory']
  assert all(n['HostConfig'][k]==c['HostConfig'][k] for k in ['MemorySwap','NanoCpus','CpuQuota','CpuPeriod','CpusetCpus'])
  assert n['HostConfig']['RestartPolicy']['Name']=='unless-stopped'
  try:
   ip=next(v['IPAddress'] for v in n['NetworkSettings']['Networks'].values() if v['IPAddress'])
   with urllib.request.urlopen('http://'+ip+':8080/api/v1/health',timeout=2) as res:
    if json.load(res)['status']=='ok':break
  except Exception:pass
  time.sleep(2)
 else:raise RuntimeError('Health check timeout')
 assert subprocess.check_output(['docker','exec',name,'sha256sum','/app/panako.jar'],text=True).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
 assert all(inspect(k)['Id']==v for k,v in other.items())
 d.update(phase='running',healthy_at=utc(),new_containers=[{'name':name,'id':n['Id']}],observation_containers=[{'name':name,'id':n['Id']}],unrelated_containers_unchanged=True);save()
except BaseException as ex:
 if frozen:subprocess.run(['docker','unpause',name],stdout=subprocess.DEVNULL)
 if recreating:
  d.update(phase='operator_review_required',error=type(ex).__name__);save();raise
 elif stopped:
  subprocess.run(['docker','update','--restart=unless-stopped',name],check=True,stdout=subprocess.DEVNULL);subprocess.run(['docker','start',name],check=True,stdout=subprocess.DEVNULL)
 d.update(phase='rolled_back',error=type(ex).__name__);save();raise
