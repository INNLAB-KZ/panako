import pathlib,json,subprocess,socket,os,time,datetime,urllib.request,urllib.parse
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-f2-move-20261004');name='refine-f2-move-20261004-refine-from-main-1'
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def inspect(n):return json.loads(subprocess.check_output(['docker','inspect',n]))[0]
proposal=json.loads((p/'compose.json').read_text());cfg=proposal['services']['refine-from-main'];assert cfg['environment']['PANAKO_WORKER_MODE']=='refine';assert cfg['environment']['KAFKA_ENABLED']=='FALSE'
assert not urllib.parse.parse_qs(urllib.parse.urlsplit(cfg['environment']['OLAF_CLICKHOUSE_URL'][5:]).query).get('socket_timeout')
s=socket.socket();s.bind(('0.0.0.0',8088));s.close()
old={c['Name']:c['Id'] for c in json.loads(subprocess.check_output(['docker','inspect',*subprocess.check_output(['docker','ps','-q'],text=True).split()]))}
d={'phase':'starting','started_at':utc(),'source':'project-panako-refine-f-1@116.202.112.101'}
def save():
 t=p/'status.tmp';t.write_text(json.dumps(d,indent=2));t.replace(p/'status.json')
save()
try:
 subprocess.run(['docker','compose','--project-name','refine-f2-move-20261004','-f',str(p/'compose.json'),'up','-d'],check=True)
 c=inspect(name);assert dict(x.split('=',1) for x in c['Config']['Env'])==cfg['environment'];assert c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==4000000000
 assert subprocess.check_output(['docker','exec',name,'sha256sum','/app/panako.jar'],text=True).split()[0]=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
 for _ in range(60):
  try:
   with urllib.request.urlopen('http://127.0.0.1:8088/api/v1/health',timeout=2) as r:
    if json.load(r)['status']=='ok':break
  except Exception:pass
  time.sleep(2)
 else:raise RuntimeError('Health timeout')
 assert all(inspect(n)['Id']==i for n,i in old.items())
 d.update(phase='running',healthy_at=utc(),name=name,new_id=c['Id'],unrelated_containers_unchanged=True);save()
except BaseException as ex:
 d.update(phase='operator_review_required',error=type(ex).__name__);save();raise
