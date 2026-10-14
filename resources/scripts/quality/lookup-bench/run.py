import pathlib,subprocess,json,os,time,datetime,urllib.request,hashlib,re
os.umask(0o077)
p=pathlib.Path('/home/bekzat/lookup-bench-20260929')
status={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':'preparing','runs':[],'cpu_limit':2,'production_queues_untouched':True}
def save():
 t=p/'status.tmp';t.write_text(json.dumps(status,indent=2));t.replace(p/'status.json')
def memory_available():
 return int(next(l.split()[1] for l in pathlib.Path('/proc/meminfo').read_text().splitlines() if l.startswith('MemAvailable:')))*1024
save()
active=None
try:
 assert memory_available()>6*1024**3
 c=json.loads(subprocess.check_output(['docker','inspect','stage1-main-20260928-stage1-1']))[0]
 assert subprocess.check_output(['docker','exec',c['Id'],'sha256sum','/app/panako.jar'],text=True).split()[0]=='5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022'
 e=dict(x.split('=',1) for x in c['Config']['Env']);assert e['CLICKHOUSE_HOST']=='10.0.1.2'
 e={k:v for k,v in e.items() if not k.startswith(('REDIS_','KAFKA_'))}
 e.update(REDIS_ENABLED='false',KAFKA_ENABLED='FALSE',MONITOR_PARALLEL_WINDOWS='2',JDK_JAVA_OPTIONS='-Xmx3584m -XX:+ExitOnOutOfMemoryError')
 (p/'bench.env').write_text(''.join(k+'='+v+'\n' for k,v in e.items()))
 urls=json.loads((p/'urls.private.json').read_text());assert len(urls)==1
 audio=p/'recording.m4a'
 if not audio.exists():
  with urllib.request.urlopen(urls[0],timeout=60) as src,audio.open('wb') as dst:
   while True:
    chunk=src.read(1024*1024)
    if not chunk:break
    dst.write(chunk)
 assert audio.stat().st_size==63874940
 status['audio_sha256']=hashlib.sha256(audio.read_bytes()).hexdigest();save()
 for mode in ['baseline','sorted']:
  assert memory_available()>5*1024**3
  active='lookup-bench-20260929-'+mode
  existing=subprocess.run(['docker','inspect',active],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
  reuse=existing.returncode==0
  if reuse:
   prior=json.loads(existing.stdout)[0]
   assert not prior['State']['Running'] and prior['State']['ExitCode']==0 and not prior['State']['OOMKilled']
   assert all((p/(mode+suffix)).exists() for suffix in ['.timing.json','.response.json','.matches.txt'])
  status.update(phase='running_'+mode);save()
  cmd=['docker','run','--name',active,'--restart=no','--cpus','2','--memory','4831838208','--memory-swap','4831838208','--env-file',str(p/'bench.env'),'-e','BENCH_SORT_HASHES='+str(mode=='sorted').lower(),'-v',str(p)+':/bench','--entrypoint','java',c['Config']['Image'],'--add-opens=java.base/java.nio=ALL-UNNAMED','-cp','/bench/overlay.jar:/app/panako.jar','be.panako.http.LookupBench','/bench/recording.m4a','/bench/'+mode]
  started=time.monotonic()
  if not reuse:
   with (p/(mode+'.log')).open('w') as log:
    proc=subprocess.Popen(cmd,stdout=log,stderr=log)
    deadline=started+2400
    while proc.poll() is None:
     if time.monotonic()>deadline:raise RuntimeError('Run timeout: '+mode)
     if memory_available()<2*1024**3:raise RuntimeError('Host memory guard')
     status.update(elapsed_run_seconds=round(time.monotonic()-started));save();time.sleep(10)
   assert proc.returncode==0, 'Benchmark exited: '+mode
  ins=json.loads(subprocess.check_output(['docker','inspect',active]))[0]
  assert not ins['State']['OOMKilled'] and ins['RestartCount']==0
  summary=json.loads((p/(mode+'.timing.json')).read_text());summary.update(mode=mode,wall_seconds=round(time.monotonic()-started,3))
  logs=subprocess.run(['docker','logs',active],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,check=True)
  text=logs.stdout;(p/(mode+'.stdout.log')).write_text(text);rows=[]
  if reuse:
   begin=datetime.datetime.fromisoformat(ins['State']['StartedAt'].replace('Z','+00:00'));end=datetime.datetime.fromisoformat(ins['State']['FinishedAt'].replace('Z','+00:00'))
   summary['wall_seconds']=round((end-begin).total_seconds(),3)
   summary['reused_completed_run']=True
  for line in text.splitlines():
   if line.startswith('BENCH_LOOKUP '):
    fields=dict(x.split('=',1) for x in line.split()[1:]);assert fields['ok']=='true';rows.append(fields)
  summary['lookup']={k:round(sum(float(r[k]) for r in rows),3) for k in ['batches','rows','connect_ms','expand_ms','execute_ms','next_ms','map_ms','total_ms']};summary['lookup_windows']=len(rows)
  status['runs'].append(summary);active=None;save()
 a=json.loads((p/'baseline.response.json').read_text());b=json.loads((p/'sorted.response.json').read_text())
 for d in [a,b]:d.pop('processing_time_ms',None)
 status.update(phase='completed',raw_matches_equal=(p/'baseline.matches.txt').read_bytes()==(p/'sorted.matches.txt').read_bytes(),response_equal=a==b,finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
except BaseException as ex:
 status.update(phase='failed',error=type(ex).__name__+': '+str(ex).split('http')[0])
finally:
 if active:subprocess.run(['docker','stop','--time','10',active],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 save()
