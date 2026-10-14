import os,pathlib,json,subprocess,copy,time,datetime,socket,urllib.request,yaml
from redis_state import Redis,env,pairs
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-lease-canary-20261007');target=pathlib.Path('/home/bekzat/project/docker-compose.yml');old=json.loads((p/'source.json').read_text());image='local/ozen-panako:refine-lease-20261007-60a75e63';project=p.name;name=project+'-refine-canary-1'
def run(a):return subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
assert not (p/'status.json').exists();assert target.read_bytes()==(p/'before.yml').read_bytes();assert inspect(old['Id'])['State']['Running'];assert old['Image']=='sha256:83b837e9bd0eb0546e413a220294834eb4ecbe4bfdd82d2b6a1cbc63892993b6'
assert not old['Mounts'];e=env(old);assert e['PANAKO_WORKER_MODE']=='refine' and e['CLICKHOUSE_HOST']=='10.0.0.12' and e['MONITOR_PARALLEL_WINDOWS']=='2' and e.get('REDIS_WORKER_THREADS')=='1'
mem={l.split(':')[0]:int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()};assert mem['MemAvailable']>7*1024**2
sock=socket.socket();sock.bind(('0.0.0.0',8094));sock.close()
cs=json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]));others={c['Name']:c['Id'] for c in cs if c['Id']!=old['Id']};assert len(cs)==6
newenv=e.copy();newenv.update(JAVA_TOOL_OPTIONS='-Xmx3584m',KAFKA_ENABLED='FALSE');hostname='refine-lease-canary-20261007'
# Environment dollar signs are literal values, not Compose substitutions.
service={'image':image,'hostname':hostname,'extra_hosts':{hostname:'127.0.0.1'},'network_mode':'host','restart':'unless-stopped','mem_limit':'4608m','cpus':3,'command':['SERVER_PORT=8094'],'environment':{k:v.replace('$','$$') for k,v in newenv.items()}}
(p/'compose.json').write_text(json.dumps({'services':{'refine-canary':service}},indent=2));spec={'name':name,'project':project,'root':str(p),'host':'157.180.26.0','port':8094,'cpus':3,'source_name':old['Name'],'source_id':old['Id'],'source_host':'157.180.26.0'};(p/'spec.json').write_text(json.dumps(spec))
newcfg=json.loads(run(['docker','compose','-p',project,'-f',str(p/'compose.json'),'config','--format','json']));assert newcfg['services']['refine-canary']['environment']==newenv
im=inspect(image);expected=dict(v.split('=',1) for v in im['Config']['Env']);expected.update(newenv);assert expected==newenv
# Keep original service definition; change only count from six to five.
doc=yaml.safe_load(target.read_text());assert doc['services']['panako-api']['deploy']['replicas']==6;doc['services']['panako-api']['deploy']['replicas']=5
proposal=p/'proposed-base.yml';proposal.write_text(yaml.safe_dump(doc,sort_keys=False));base=['docker','compose','-p','project','--project-directory',str(target.parent)]
a=json.loads(run(base+['-f',str(target),'config','--format','json']));b=json.loads(run(base+['-f',str(proposal),'config','--format','json']));want=copy.deepcopy(a);want['services']['panako-api']['deploy']['replicas']=5;assert b==want
r=Redis(e);stream='panako.monitor.refine.request';group='panako-worker-refine'
def pending():
 result=[]
 for v in r.cmd('XINFO','CONSUMERS',stream,group):
  owner=pairs(v)['name']
  if owner.startswith(old['Config']['Hostname']+'-'):
   result+=r.cmd('XPENDING',stream,group,'-','+',1000,owner)
 return result
d={'phase':'draining','started_at':utc(),'source':old['Name'],'source_id':old['Id'],'name':name};frozen=False;stopped=False

def save():
 tmp=p/'status.tmp';tmp.write_text(json.dumps(d,indent=2));tmp.replace(p/'status.json')
save()
try:
 deadline=time.monotonic()+180
 while time.monotonic()<deadline:
  if not pending():
   run(['docker','pause',old['Id']]);frozen=True;time.sleep(5)
   if not pending():break
   run(['docker','unpause',old['Id']]);frozen=False
  time.sleep(1)
 d['pending_before_stop']=pending();d['idle_drain']=frozen;save()
 assert target.read_bytes()==(p/'before.yml').read_bytes()
 run(['docker','update','--restart=no',old['Id']])
 if frozen:run(['docker','kill',old['Id']]);frozen=False
 else:run(['docker','stop','--timeout','30',old['Id']])
 stopped=True;d['pending_after_stop']=pending();d['source_stopped_at']=utc();save()
 tmp=target.with_name('docker-compose.lease.tmp');tmp.write_bytes(proposal.read_bytes());os.chmod(tmp,target.stat().st_mode & 0o777);os.replace(tmp,target)
 run(['docker','compose','-p',project,'-f',str(p/'compose.json'),'up','-d','--pull','never'])
 c=inspect(name);assert env(c)==newenv;assert c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==3000000000
 assert run(['docker','exec',name,'sha256sum','/app/panako.jar']).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
 for _ in range(60):
  try:
   with urllib.request.urlopen('http://127.0.0.1:8094/api/v1/health',timeout=2) as res:healthy=json.load(res).get('status')=='ok'
  except Exception:healthy=False
  if healthy:break
  time.sleep(2)
 assert healthy
 assert all(inspect(n)['Id']==i and inspect(n)['State']['Running'] for n,i in others.items())
 d.update(phase='running',healthy_at=utc(),new_id=c['Id'],unrelated_containers_unchanged=True);save();print(json.dumps(d))
except BaseException as ex:
 if frozen:subprocess.run(['docker','unpause',old['Id']])
 # Failed pilot returns to original source and original persistent count.
 subprocess.run(['docker','update','--restart=no',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 subprocess.run(['docker','stop','--timeout','30',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 target.write_bytes((p/'before.yml').read_bytes())
 if stopped:
  run(['docker','update','--restart='+old['HostConfig']['RestartPolicy']['Name'],old['Id']]);run(['docker','start',old['Id']])
 d.update(phase='failed_rolled_back',error=type(ex).__name__);save();raise
