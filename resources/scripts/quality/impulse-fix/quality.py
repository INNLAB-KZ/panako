import pathlib,subprocess,json,os,time,datetime,hashlib
os.umask(0o077)
p=pathlib.Path('/home/bekzat/impulse-fix-20260929');s={'phase':'waiting_for_oom_replay','production_queues_untouched':True};active=None

def save():
 t=p/'quality.tmp';t.write_text(json.dumps(s,indent=2));t.replace(p/'quality.json')
def available():return int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
save()
try:
 deadline=time.monotonic()+1300
 while True:
  if time.monotonic()>deadline:raise RuntimeError('Replay wait timeout')
  try:prior=json.loads((p/'status.json').read_text())
  except (FileNotFoundError, json.JSONDecodeError):
   time.sleep(1);continue
  if prior['phase']=='completed':break
  if prior['phase']=='failed':raise RuntimeError('OOM replay failed; quality run blocked')
  if time.monotonic()>deadline:raise RuntimeError('Replay wait timeout')
  time.sleep(10)
 assert hashlib.sha256((p/'positive.m4a').read_bytes()).hexdigest()=='1dc05b7925f4905235b9bdd17c0838a27f31a16fb409877067e6a99732ce9699'
 s['runs']=[]
 for mode in ['baseline','candidate']:
  assert available()>6*1024**3
  active='impulse-positive-20260929-'+mode
  jar='/app/panako.jar' if mode=='baseline' else '/probe/panako.jar'
  opts='-Xmx3584m -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/probe/positive-'+mode+'.gc.log:time,uptime,level,tags'
  cmd=['docker','run','-d','--name',active,'--restart=no','--cpus','2','--memory','4831838208','--memory-swap','4831838208','--env-file',str(p/'probe.env'),'-e','JDK_JAVA_OPTIONS='+opts,'-v',str(p)+':/probe','--entrypoint','java','local/ozen-panako:input-pilot-20260925-5d50209b','--add-opens=java.base/java.nio=ALL-UNNAMED','-cp','/probe/probe.jar:'+jar,'be.panako.http.RecognitionProbe','/probe/positive.m4a','/probe/positive-'+mode]
  subprocess.check_output(cmd,text=True)
  s.update(phase='running_'+mode);save();begin=time.monotonic()
  while True:
   state=json.loads(subprocess.check_output(['docker','inspect',active]))[0]['State']
   if not state['Running']:
    if state['ExitCode']!=0:raise RuntimeError(mode+' failed: exit '+str(state['ExitCode']))
    break
   if available()<2*1024**3:raise RuntimeError('Host memory guard')
   if time.monotonic()-begin>1200:raise RuntimeError(mode+' timeout')
   s['elapsed_run_seconds']=round(time.monotonic()-begin);save();time.sleep(10)
  s['runs'].append({'mode':mode,**json.loads((p/('positive-'+mode+'.metrics.json')).read_text())});active=None;save()
 a=json.loads((p/'positive-baseline.json').read_text());b=json.loads((p/'positive-candidate.json').read_text())
 for d in [a,b]:d.pop('processing_time_ms',None)
 same_raw=(p/'positive-baseline.raw.tsv').read_bytes()==(p/'positive-candidate.raw.tsv').read_bytes()
 s.update(phase='passed' if same_raw and a==b else 'mismatch',raw_equal=same_raw,response_equal=a==b)
except BaseException as ex:s.update(phase='failed',error=type(ex).__name__+': '+str(ex).split('http')[0])
finally:
 if active:subprocess.run(['docker','stop','--time','5',active],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 s['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();save()
