import json,subprocess,pathlib,os,time,datetime,urllib.parse,urllib.request,sys,copy
os.umask(0o077)
root=pathlib.Path('/home/bekzat/refine-recovery-20261002');root.mkdir(exist_ok=True)
name=sys.argv[1]
def inspect(n):return json.loads(subprocess.check_output(['docker','inspect',n]))[0]
def env(c):return dict(x.split('=',1) for x in c['Config']['Env'])
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
c=inspect(name);e=env(c)
assert c['State']['Running'] and e.get('PANAKO_WORKER_MODE')=='refine'
assert c['Config']['Image']=='local/ozen-panako:refine-fix-20260927-5d50209b'
logs=subprocess.check_output(['docker','logs','--since','1h','--tail','8000',name],stderr=subprocess.STDOUT).decode(errors='replace')
assert logs.count('Waiting for cancelled windows to exit')>=100
assert 'Redis monitor completed:' not in logs
assert 'Redis task ownership lost' in logs
labels=c['Config']['Labels'];service=labels['com.docker.compose.service'];project=labels['com.docker.compose.project']
base=['docker','compose','--project-name',project]
for f in labels['com.docker.compose.project.config_files'].split(','):base+=['-f',f]
url=e['OLAF_CLICKHOUSE_URL'];parsed=urllib.parse.urlsplit(url[5:]);params=urllib.parse.parse_qs(parsed.query)
assert not any(k in params for k in ['socket_timeout','connection_timeout','connection_request_timeout'])
new_url=url+('&' if '?' in url else '?')+'socket_timeout=60000&connection_timeout=5000&connection_request_timeout=5000'
expected=e.copy();expected['OLAF_CLICKHOUSE_URL']=new_url
proposal={'services':{service:{'environment':{'OLAF_CLICKHOUSE_URL':new_url}}}}
override=root/'compose.jdbc-timeout.yml';assert not override.exists()
override.write_text(json.dumps(proposal))
a=json.loads(subprocess.check_output(base+['config','--format','json']));b=json.loads(subprocess.check_output(base+['-f',str(override),'config','--format','json']))
assert b['services'][service]['environment']['OLAF_CLICKHOUSE_URL']==new_url
b['services'][service]['environment']['OLAF_CLICKHOUSE_URL']=a['services'][service]['environment']['OLAF_CLICKHOUSE_URL'];assert a==b
allc=json.loads(subprocess.check_output(['docker','inspect',*subprocess.check_output(['docker','ps','-q'],text=True).split()]))
others={v['Name']:v['Id'] for v in allc if v['Id']!=c['Id']}
(root/'before.inspect.json').write_text(json.dumps(c))
d={'phase':'recreating','started_at':utc(),'name':name,'old_id':c['Id'],'blocked_warnings':logs.count('Waiting for cancelled windows to exit')}
def save():
 p=root/'status.tmp';p.write_text(json.dumps(d,indent=2));p.replace(root/'status.json')
save()
try:
 subprocess.run(base+['-f',str(override),'up','-d','--no-deps','--force-recreate',service],check=True)
 n=inspect(name);assert env(n)==expected and n['Config']['Image']==c['Config']['Image']
 for k in ['Memory','MemorySwap','NanoCpus','CpuQuota','CpuPeriod','CpusetCpus','RestartPolicy']:assert n['HostConfig'][k]==c['HostConfig'][k],k
 assert n['Config']['Hostname']==c['Config']['Hostname']
 for i in range(60):
  n=inspect(name);assert n['State']['Running']
  host='127.0.0.1' if n['HostConfig']['NetworkMode']=='host' else next(v['IPAddress'] for v in n['NetworkSettings']['Networks'].values() if v['IPAddress'])
  port=int(next((v.split('=',1)[1] for v in n['Config'].get('Cmd',[]) if v.startswith('SERVER_PORT=')), expected.get('HTTP_PORT','8080')))
  try:
   with urllib.request.urlopen('http://'+host+':'+str(port)+'/api/v1/health',timeout=2) as r:
    if json.load(r)['status']=='ok':break
  except Exception:pass
  time.sleep(2)
 else:raise RuntimeError('Health timeout')
 assert all(inspect(k)['Id']==v for k,v in others.items())
 d.update(phase='running',healthy_at=utc(),new_id=n['Id'],other_containers_unchanged=True);save()
except BaseException as ex:
 d.update(phase='operator_review_required',error=type(ex).__name__);save();raise
