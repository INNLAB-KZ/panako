"""Compare one histogram optimization offline, using the same two downloaded files."""
import datetime,json,os,pathlib,subprocess,sys,time
os.umask(0o077);p=pathlib.Path('/home/bekzat/latency-20261012');run=lambda a:subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
variant=sys.argv[1] if len(sys.argv)>1 else 'candidate'
assert variant in ['candidate','cache-candidate']
assert json.loads((p/'status.json').read_text())['phase']=='complete'
assert not (p/(variant+'-status.json')).exists()
if variant=='cache-candidate':assert json.loads((p/'candidate-status.json').read_text())['phase']=='complete'
state={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':'running','results':[],'production_changed':False}
def save():
 t=p/(variant+'-status.tmp');t.write_text(json.dumps(state,indent=2));t.replace(p/(variant+'-status.json'))
def canonical(x):
 if isinstance(x,dict):return {k:canonical(v) for k,v in x.items() if k!='processing_time_ms'}
 if isinstance(x,list):
  a=[canonical(v) for v in x]
  return sorted(a,key=lambda v:json.dumps(v,sort_keys=True)) if a and isinstance(a[0],dict) else a
 return x
try:
 for role in ['stage1','refine']:
  body=json.loads((p/(role+'-request.json')).read_text());baseline=json.loads((p/'output'/(role+'-response.json')).read_text())
  c=json.loads(run(['docker','inspect','panako-isolated-pair-'+role+'-1']))[0]
  mem={s.split(':')[0]:int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()};assert mem['MemAvailable']>7*1024**2
  segments=','.join(str(int(s['start_seconds']))+':'+str(int(s['end_seconds'])) for s in body.get('segments',[]) or []) or 'ALL'
  name='latency-'+variant+'-20261012-'+role
  cid=run(['docker','run','-d','--name',name,'--restart=no','--network=host','--cpus=3','--memory=4608m','--memory-swap=4608m','--env-file',str(p/(role+'.env')),'--mount','type=bind,src='+str(p)+',dst=/input,readonly','--mount','type=bind,src='+str(p/'output')+',dst=/output','--entrypoint','java',c['Image'],'--add-opens=java.base/java.nio=ALL-UNNAMED','-Xmx3584m','-XX:StartFlightRecording=filename=/output/'+role+'-'+variant+'.jfr,settings=profile,dumponexit=true,maxsize=128m','-Xlog:gc:file=/output/'+role+'-'+variant+'-gc.log:time,uptime,level,tags','-cp','/input/'+variant+'-classes:/input/classes:/input/panako.jar','be.panako.http.OfflineProfileProbe','/output/'+role+'.audio','/output/'+role+'-'+variant+'-response.json',segments,body['recording_id']]).strip()
  state.update(role=role,container=cid);save();start=time.monotonic()
  while True:
   status=json.loads(run(['docker','inspect',cid]))[0]
   if not status['State']['Running']:break
   if time.monotonic()-start>1200:
    run(['docker','stop','--timeout','10',cid]);raise RuntimeError('Candidate timeout')
   state['elapsed_seconds']=round(time.monotonic()-start);save();time.sleep(10)
  logs=subprocess.run(['docker','logs',cid],capture_output=True,text=True);(p/(name+'.log')).write_text(logs.stdout+logs.stderr)
  assert status['State']['ExitCode']==0 and not status['State']['OOMKilled']
  actual=json.loads((p/'output'/(role+'-'+variant+'-response.json')).read_text())
  same=canonical(actual)==canonical(baseline)
  state['results'].append({'role':role,'recognition_identical':same,'baseline_ms':baseline['processing_time_ms'],'candidate_ms':actual['processing_time_ms'],'unique_tracks':actual['unique_tracks_count']});save()
  assert same,'Recognition difference: candidate rejected'
 state.update(phase='complete',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat());save()
except Exception as ex:
 state.update(phase='failed',error=type(ex).__name__);save();raise
print(json.dumps(state))
