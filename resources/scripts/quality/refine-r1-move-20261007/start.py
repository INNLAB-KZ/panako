import pathlib,json,subprocess,socket,os,time,datetime,urllib.request,sys
os.umask(0o077);p=pathlib.Path(sys.argv[1]);spec=json.loads((p/'spec.json').read_text());name=spec['name']
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def inspect(n):return json.loads(subprocess.check_output(['docker','inspect',n]))[0]
cfg=json.loads((p/'compose.json').read_text())['services']['refine-from-main'];assert cfg['environment']['PANAKO_WORKER_MODE']=='refine' and cfg['environment']['KAFKA_ENABLED']=='FALSE'
assert not (p/'status.json').exists();sock=socket.socket();sock.bind(('0.0.0.0',spec['port']));sock.close()
mem=dict((x.split(':')[0],int(x.split()[1])) for x in pathlib.Path('/proc/meminfo').read_text().splitlines());assert mem['MemAvailable']>7*1024**2
old={c['Name']:c['Id'] for c in json.loads(subprocess.check_output(['docker','inspect',*subprocess.check_output(['docker','ps','-q'],text=True).split()]))}
d={'phase':'starting','started_at':utc(),'source':spec['source_name'],'host':spec['host']}
def save():
 t=p/'status.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'status.json')
save()
try:
 subprocess.run(['docker','compose','--project-name',spec['project'],'-f',str(p/'compose.json'),'up','-d'],check=True)
 c=inspect(name);assert dict(x.split('=',1) for x in c['Config']['Env'])==cfg['environment'];assert c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==int(spec['cpus']*10**9)
 assert c['Config']['Hostname']==cfg['hostname']
 assert subprocess.check_output(['docker','exec',name,'sha256sum','/app/panako.jar'],text=True).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
 for _ in range(60):
  try:
   with urllib.request.urlopen('http://127.0.0.1:'+str(spec['port'])+'/api/v1/health',timeout=2) as r:
    if json.load(r)['status']=='ok':break
  except Exception:pass
  time.sleep(2)
 else:raise RuntimeError('Health timeout')
 assert all(inspect(n)['Id']==i for n,i in old.items())
 d.update(phase='running',healthy_at=utc(),name=name,new_id=c['Id'],unrelated_containers_unchanged=True);save();print(json.dumps(d))
except BaseException as ex:
 d.update(phase='operator_review_required',error=type(ex).__name__);save();raise
