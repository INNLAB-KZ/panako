"""Roll out validated images to the isolated pair only; preserve rollback manifests."""
import datetime,json,os,pathlib,subprocess,sys,time,urllib.request
sys.path.insert(0,'/home/bekzat/isolated-pair-20261009')
from redis_state import Redis,env
os.umask(0o077)
p=pathlib.Path('/home/bekzat/latency-20261012');base=pathlib.Path('/home/bekzat/isolated-pair-20261009')
compose=base/'compose.json';status_path=base/'status.json';rollback=sys.argv[1:]==['--rollback']
assert not sys.argv[1:] or rollback
release=json.loads((p/'release.json').read_text())
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
def stamp():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def atomic(path,body):
 temp=path.with_suffix('.tmp');temp.write_text(json.dumps(body,indent=2));temp.replace(path)
if not rollback:
 gate=json.loads((p/'cache-candidate-status.json').read_text())
 assert gate['phase']=='complete' and len(gate['results'])==2
 assert all(x['recognition_identical'] and x['candidate_ms']<x['baseline_ms'] for x in gate['results'])
 assert not (p/'compose-before.json').exists(),'Deployment already attempted; inspect status or use rollback'
 (p/'compose-before.json').write_bytes(compose.read_bytes());(p/'deployment-before.json').write_bytes(status_path.read_bytes())
original=json.loads((p/'deployment-before.json').read_text());desired=json.loads((p/'compose-before.json').read_text())
snapshot=inspect(original['services'][0]['name']);credentials=env(snapshot)
assert credentials['REDIS_HOST']=='10.0.0.4'
process_env=dict(os.environ,PAIR_REDIS_PASSWORD=credentials['REDIS_PASSWORD'])
state={'phase':'rollback' if rollback else 'deploying','started_at':stamp(),'roles':[]}
state_path=p/('rollback-status.json' if rollback else 'deployment-status.json')
def save():atomic(state_path,state)
def command(args):
 result=subprocess.run(args,env=process_env,capture_output=True,text=True)
 with (p/'deployment.log').open('a') as f:f.write(result.stdout+result.stderr)
 if result.returncode:raise RuntimeError('Docker command failed; protected deployment log retained')
 return result.stdout.strip()
def up(role):command(['docker','compose','-p','panako-isolated-pair','-f',str(compose),'up','-d','--no-deps','--no-build',role])
def healthy(item):
 for _ in range(60):
  c=inspect(item['name'])
  try:
   with urllib.request.urlopen('http://127.0.0.1:'+str(item['port'])+'/api/v1/health',timeout=2) as x:
    if c['State']['Running'] and json.load(x).get('status')=='ok':return c
  except Exception:pass
  time.sleep(1)
 raise RuntimeError('Health check failed: '+item['role'])
def check_unrelated():
 for name,cid in original['unrelated'].items():
  c=inspect(name);assert c['Id']==cid and c['State']['Running']
changed=[];paused=None
try:
 check_unrelated();save()
 for item in original['services']:
  role=item['role'];old=inspect(item['name']);e=env(old)
  assert e['REDIS_HOST']=='10.0.0.4' and e['CLICKHOUSE_HOST']=='10.0.1.2'
  if not rollback:
   assert old['Image']==release['old_images'][role]
   (p/('inspect-before-'+role+'.json')).write_text(json.dumps(old))
  r=Redis(e);deadline=time.monotonic()+1800
  while True:
   state.update(waiting_for_idle=role);save()
   if time.monotonic()>deadline:raise RuntimeError('Idle wait deadline exceeded')
   if r.cmd('XPENDING',item['stream'],item['group'])[0]:time.sleep(10);continue
   command(['docker','pause',old['Id']]);paused=old['Id']
   if r.cmd('XPENDING',item['stream'],item['group'])[0]:
    command(['docker','unpause',paused]);paused=None;time.sleep(10);continue
   break
  desired['services'][role]['image']=release['old_images'][role] if rollback else release['images'][role]
  atomic(compose,desired);changed.append(role);up(role);paused=None
  new=healthy(item)
  assert env(new)==e,'Unexpected environment change'
  for key in ['Memory','MemorySwap','NanoCpus']:assert new['HostConfig'][key]==old['HostConfig'][key]
  expected='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e' if rollback else release['jar_sha256']
  actual=command(['docker','exec',new['Id'],'sha256sum','/app/panako.jar']).split()[0];assert actual==expected
  state['roles'].append({'role':role,'old_id':old['Id'],'new_id':new['Id'],'image':new['Image'],'jar_sha256':actual,'health':True,'at':stamp()});save()
  current=json.loads(status_path.read_text())
  for service in current['services']:
   if service['role']==role:service['id']=new['Id']
  current['latency_release_at']=stamp();current['jar_sha256']=actual;atomic(status_path,current)
 check_unrelated();state.update(phase='rolled_back' if rollback else 'healthy',finished_at=stamp());save()
except Exception as ex:
 if paused:subprocess.run(['docker','unpause',paused],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 state.update(phase='failed',error=type(ex).__name__,changed_roles=changed);save()
 if not rollback and changed:
  result=subprocess.run(['python3',str(p/'deploy-pair.py'),'--rollback'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  state['automatic_rollback_exit']=result.returncode;save()
 raise
print(json.dumps(state))
