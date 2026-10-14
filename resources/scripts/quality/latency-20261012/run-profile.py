"""Two bounded offline replays from exact failed requests, using the deployed JAR."""
import datetime,hashlib,json,os,pathlib,subprocess,sys,time
sys.path.insert(0,'/home/bekzat/isolated-pair-20261009')
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path('/home/bekzat/latency-20261012')
assert not (p/'status.json').exists()
assert hashlib.sha256((p/'panako.jar').read_bytes()).hexdigest()=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
run=lambda a:subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
state={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':'preparing','results':[],'redis_publication':False}
def save():
 tmp=p/'status.tmp';tmp.write_text(json.dumps(state,indent=2));tmp.replace(p/'status.json')
def execute(name,image,envfile,memory,cpu,args,timeout):
 cid=run(['docker','run','-d','--name',name,'--restart=no','--network=host','--cpus',str(cpu),'--memory',memory,'--memory-swap',memory,'--env-file',str(envfile),'--mount','type=bind,src='+str(p)+',dst=/input,readonly','--mount','type=bind,src='+str(p/'output')+',dst=/output','--entrypoint','java',image,'--add-opens=java.base/java.nio=ALL-UNNAMED',*args]).strip()
 state.update(container=cid,current=name);save();start=time.monotonic()
 while True:
  c=json.loads(run(['docker','inspect',cid]))[0]
  if not c['State']['Running']:break
  if time.monotonic()-start>timeout:
   run(['docker','stop','--timeout','10',cid]);raise RuntimeError('Probe timeout')
  state['elapsed_seconds']=round(time.monotonic()-start);save();time.sleep(10)
 logs=subprocess.run(['docker','logs',cid],capture_output=True,text=True)
 (p/(name+'.log')).write_text(logs.stdout+logs.stderr)
 assert c['State']['ExitCode']==0 and not c['State']['OOMKilled'],'Probe failed; inspect protected log'
 return cid
try:
 for role,mid,rid in [('stage1','1791695446209-0','01a1295a-d888-778d-bcb0-b3f395d79a4b'),('refine','1791687397234-0','01a1287e-e6a8-7d17-a894-f09f2a57a849')]:
  c=json.loads(run(['docker','inspect','panako-isolated-pair-'+role+'-1']))[0];e=env(c);r=Redis(e)
  assert e['REDIS_HOST']=='10.0.0.4' and e['CLICKHOUSE_HOST']=='10.0.1.2'
  stream='panako.monitor.'+('refine.' if role=='refine' else '')+'request'
  rows=r.cmd('XRANGE',stream,mid,mid);assert len(rows)==1
  body=json.loads(pairs(rows[0][1])['value']);assert body['recording_id']==rid
  (p/(role+'-request.json')).write_text(json.dumps(body));(p/(role+'.url')).write_text(body['audio_url'])
  # Preserve production recognition settings; remove queue and API credentials.
  e={k:v for k,v in e.items() if not k.startswith(('REDIS_','KAFKA_')) and k!='API_KEY'}
  e.update(REDIS_ENABLED='false',KAFKA_ENABLED='FALSE')
  envfile=p/(role+'.env');envfile.write_text(''.join(k+'='+v+'\n' for k,v in e.items()))
  state.update(phase='download',role=role);save()
  execute('latency-download-20261012-'+role,c['Image'],envfile,'384m',1,['-Xmx256m','-cp','/input/classes:/input/panako.jar','be.panako.http.DownloadProbe','/input/'+role+'.url','/output/'+role+'.audio'],900)
  audio=p/'output'/(role+'.audio')
  mem={s.split(':')[0]:int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()};assert mem['MemAvailable']>7*1024**2
  state.update(phase='profile');save()
  segments=','.join(str(int(s['start_seconds']))+':'+str(int(s['end_seconds'])) for s in body.get('segments',[]) or []) or 'ALL'
  execute('latency-profile-20261012-'+role,c['Image'],envfile,'4608m',3,['-Xmx3584m','-XX:StartFlightRecording=filename=/output/'+role+'.jfr,settings=profile,dumponexit=true,maxsize=128m','-Xlog:gc:file=/output/'+role+'-gc.log:time,uptime,level,tags','-cp','/input/classes:/input/panako.jar','be.panako.http.OfflineProfileProbe','/output/'+role+'.audio','/output/'+role+'-response.json',segments,rid],1200)
  response=json.loads((p/'output'/(role+'-response.json')).read_text());assert response['status']=='ok' and response['recording_id']==rid
  state['results'].append({'role':role,'recording_id':rid,'request_id':mid,'processing_time_ms':response['processing_time_ms'],'duration_seconds':response['duration_seconds'],'audio_bytes':audio.stat().st_size,'audio_sha256':hashlib.sha256(audio.read_bytes()).hexdigest()});save()
 state.update(phase='complete',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat());save()
except Exception as ex:
 state.update(phase='failed',error=type(ex).__name__);save();raise
print(json.dumps(state))
