import pathlib,json,subprocess,time,os,sys,datetime
from redis_state import Redis,env
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-h-move-20261006');spec=json.loads((p/'spec.json').read_text());d=json.loads((p/'status.json').read_text());name=d['name']
def inspect():return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
c=inspect();assert c['Id']==d['new_id'] and c['State']['Running'];r=Redis(env(c));owner=c['Config']['Hostname']+'-1-1';stream='panako.monitor.refine.request';group='panako-worker-refine'
def pending():return r.cmd('XPENDING',stream,group,'-','+',10,owner)
def run(*a):return subprocess.check_output(a,text=True)
old={v['Name']:v['Id'] for v in json.loads(run('docker','inspect',*run('docker','ps','-q').split())) if v['Id']!=c['Id']};deadline=time.monotonic()+600;frozen=False
out={'phase':'draining','reason':'Main RTT 35ms; first full-hour task 685848ms exceeds target','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat()};(p/'hold.json').write_text(json.dumps(out))
try:
 while time.monotonic()<deadline:
  if not pending():
   assert inspect()['Id']==c['Id'];run('docker','pause',name);frozen=True;time.sleep(6)
   if not pending():break
   run('docker','unpause',name);frozen=False
  time.sleep(.2)
 out['pending_before_stop']=len(pending());cfg=p/'compose.json';raw=cfg.read_bytes();(p/'before-hold-compose.json').write_bytes(raw);obj=json.loads(raw);svc=obj['services']['refine-from-main'];svc['restart']='no';svc.setdefault('deploy',{})['replicas']=0;tmp=p/'compose.hold.tmp';tmp.write_text(json.dumps(obj,indent=2));tmp.replace(cfg)
 run('docker','update','--restart=no',name)
 if frozen:run('docker','kill',name);frozen=False
 else:run('docker','stop','--timeout','30',name)
 n=inspect();assert not n['State']['Running'] and n['HostConfig']['RestartPolicy']['Name']=='no';out['pending_after_stop']=len(pending())
 for other,identifier in old.items():
  v=json.loads(run('docker','inspect',other))[0];assert v['Id']==identifier and v['State']['Running']
 out.update(phase='stopped',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),unrelated_containers_unchanged=True);(p/'hold.json').write_text(json.dumps(out,indent=2));print(json.dumps(out))
except BaseException:
 if frozen:subprocess.run(['docker','unpause',name])
 raise
