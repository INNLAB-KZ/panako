import pathlib,subprocess,json,os,time,datetime,urllib.request,hashlib
os.umask(0o077)
p=pathlib.Path('/home/bekzat/impulse-fix-20260929');name='impulse-fix-20260929'
s={'phase':'preparing','production_queues_untouched':True,'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
def save(): (p/'status.json').write_text(json.dumps(s,indent=2))
def available():return int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
save();started=False
try:
 assert available()>7*1024**3
 c=json.loads(subprocess.check_output(['docker','inspect','stage1-main-20260928-stage1-1']))[0]
 assert subprocess.check_output(['docker','exec',c['Id'],'sha256sum','/app/panako.jar'],text=True).split()[0]=='5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022'
 e=dict(x.split('=',1) for x in c['Config']['Env']);assert e['CLICKHOUSE_HOST']=='10.0.1.2'
 e={k:v for k,v in e.items() if not k.startswith(('REDIS_','KAFKA_'))}
 e.update(REDIS_ENABLED='false',KAFKA_ENABLED='FALSE',MONITOR_PARALLEL_WINDOWS='2',JDK_JAVA_OPTIONS='-Xmx3584m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/probe/oom.hprof -Xlog:gc*:file=/probe/gc.log:time,uptime,level,tags')
 (p/'probe.env').write_text(''.join(k+'='+v+'\n' for k,v in e.items()))
 audio=p/'recording.m4a'
 if not audio.exists():os.link('/home/bekzat/oom-replay-20260929/recording.m4a', audio)
 assert audio.stat().st_size==43098830
 assert hashlib.sha256((p/'panako.jar').read_bytes()).hexdigest()=='83fdabf6150d92060d85b678430eaa8ea3f7eb1d4b9bb82584e8e878dd2a93d4'
 s['audio_sha256']=hashlib.sha256(audio.read_bytes()).hexdigest()
 args=['docker','run','-d','--name',name,'--restart=no','--cpus','2','--memory','4831838208','--memory-swap','4831838208','--env-file',str(p/'probe.env'),'-v',str(p)+':/probe','--entrypoint','java',c['Config']['Image'],'--add-opens=java.base/java.nio=ALL-UNNAMED','-cp','/probe/probe.jar:/probe/panako.jar','be.panako.http.RecognitionProbe','/probe/recording.m4a','/probe/candidate']
 s['container_id']=subprocess.check_output(args,text=True).strip();started=True;s['phase']='running';save();begin=time.monotonic()
 while True:
  state=json.loads(subprocess.check_output(['docker','inspect',name]))[0]['State']
  if not state['Running']:
   s.update(phase='completed' if state['ExitCode']==0 else 'failed',exit_code=state['ExitCode'],oom_killed=state['OOMKilled']);break
  if available()<2*1024**3:raise RuntimeError('Host memory guard')
  if time.monotonic()-begin>1200:raise RuntimeError('Replay timeout')
  s['elapsed_seconds']=round(time.monotonic()-begin);save();time.sleep(10)
except BaseException as ex:s.update(phase='failed',error=type(ex).__name__)
finally:
 if started:
  subprocess.run(['docker','stop','--time','5',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  logs=subprocess.run(['docker','logs','--timestamps',name],capture_output=True,text=True)
  (p/'stdout.private.log').write_text(logs.stdout);(p/'stderr.private.log').write_text(logs.stderr)
  s['java_heap_oom']='Terminating due to java.lang.OutOfMemoryError: Java heap space' in logs.stdout+logs.stderr
 s['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();save()
print(json.dumps(s))
