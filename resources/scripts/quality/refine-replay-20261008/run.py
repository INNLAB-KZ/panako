import pathlib,subprocess,json,os,urllib.request,time,datetime,hashlib
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-replay-20261008');out=p/'output';out.mkdir(mode=0o700,exist_ok=True);name='refine-offline-replay-20261008';e=json.loads((p/'environment.json').read_text());req=json.loads((p/'request.json').read_text());state={'phase':'preparing','at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
def save():
 t=p/'status.tmp';t.write_text(json.dumps(state,indent=2));t.replace(p/'status.json')
def run(a):return subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
assert not (p/'status.json').exists();assert (p/'config.properties').exists();assert e['CLICKHOUSE_HOST']=='10.0.0.12' and e['JAVA_TOOL_OPTIONS']=='-Xmx3584m' and e['MONITOR_PARALLEL_WINDOWS']=='2'
assert hashlib.sha256((p/'panako.jar').read_bytes()).hexdigest()=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
image='sha256:0e044d8de525bd27c7dac82fbc182205053a85e75302861cadcde5759019a77b';assert json.loads(run(['docker','image','inspect',image]))[0]['Id']==image
mem={l.split(':')[0]:int(l.split()[1]) for l in pathlib.Path('/proc/meminfo').read_text().splitlines()};assert mem['MemAvailable']>16*1024**2
old=json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]));ids={c['Name']:c['Id'] for c in old};save()
# Audio fetched by production Java downloader before timing.
assert (p/'audio.m4a').stat().st_size==64210427
state['audio_sha256']=hashlib.sha256((p/'audio.m4a').read_bytes()).hexdigest();state['audio_bytes']=(p/'audio.m4a').stat().st_size
# Offline entry point never creates consumers or a HTTP server.
e.update(REDIS_ENABLED='false',KAFKA_ENABLED='FALSE');assert not any('\n' in v for v in e.values());(p/'replay.env').write_text(''.join(k+'='+v+'\n' for k,v in e.items()))
segments=','.join(str(int(s['start_seconds']))+':'+str(int(s['end_seconds'])) for s in req['segments']);assert segments=='0:3233,3254:3600'
cmd=['docker','run','-d','--name',name,'--restart=no','--network=host','--cpus=3','--memory=4608m','--memory-swap=4608m','--env-file',str(p/'replay.env'),'--mount','type=bind,src='+str(p)+',dst=/input,readonly','--mount','type=bind,src='+str(out)+',dst=/output','--entrypoint','java',image,'--add-opens=java.base/java.nio=ALL-UNNAMED','-Xlog:gc:file=/output/gc.log:time,uptime,level,tags','-cp','/input/classes:/input/panako.jar','be.panako.http.SegmentReplayProbe','/input/audio.m4a','/output/replay',segments]
identifier=run(cmd).strip();state.update(phase='running',container_id=identifier,name=name,started_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),segments=segments);save();start=time.monotonic()
try:
 while True:
  c=json.loads(run(['docker','inspect',identifier]))[0]
  if not c['State']['Running']:
   state.update(phase='complete' if c['State']['ExitCode']==0 else 'failed',exit_code=c['State']['ExitCode'],oom=c['State']['OOMKilled'],finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat());break
  if time.monotonic()-start>1500:
   run(['docker','stop','--timeout','10',identifier]);state.update(phase='timeout');break
  try:
   stat=run(['docker','exec',identifier,'cat','/sys/fs/cgroup/cpu.stat']);cpu={l.split()[0]:int(l.split()[1]) for l in stat.splitlines()};state['cpu_stat']=cpu
  except subprocess.CalledProcessError:pass
  state['elapsed_s']=round(time.monotonic()-start,1);save()
  with (out/'samples.jsonl').open('a') as f:f.write(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsed_s':state['elapsed_s'],'cpu_stat':state.get('cpu_stat')})+'\n')
  time.sleep(10)
finally:
 logs=subprocess.run(['docker','logs','--timestamps',identifier],capture_output=True,text=True);(out/'container.log').write_text(logs.stdout+logs.stderr)
 state['unrelated_containers_unchanged']=all(json.loads(run(['docker','inspect',n]))[0]['Id']==v for n,v in ids.items());save()
print(json.dumps(state))
