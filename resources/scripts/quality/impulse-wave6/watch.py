from redis_state import Redis,env,state
from monitor_errors import count_java_oom
import pathlib,json,subprocess,time,datetime,urllib.request,re,os
os.umask(0o077)
root=pathlib.Path('/home/bekzat/impulse-wave6-20260930')
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
while True:
 if (root/'deployment.json').exists():
  d=json.loads((root/'deployment.json').read_text())
  if d['phase']=='running':break
  if d.get('error'):raise SystemExit('Deployment failed')
 time.sleep(2)
expected={x['name']:x['id'] for x in d['observation_containers']}
start=time.monotonic();summary={'started_at':utc(),'duration_seconds':1800,'alerts':[]};seen={};responses={};cursor=str(int(time.time()*1000))+'-0'
while time.monotonic()-start<1800:
 samples=[]
 for name,identifier in expected.items():
  c=json.loads(subprocess.check_output(['docker','inspect',name]))[0]
  if c['Id']!=identifier:raise RuntimeError('Container changed: '+name)
  e=env(c);r=Redis(e);s=state(c,r,e)
  for p in s['pending']:
   if p.get('recording_id'):seen[p['recording_id']]=name
  logs=subprocess.run(['docker','logs','--since',d['healthy_at'],'--tail','100000',identifier],capture_output=True,text=True);text=logs.stdout+logs.stderr
  errors={k:text.count(k) for k in ['Window processing failed','Failed to process query queue','Redis task heartbeat failed','Redis task ownership lost','Redis worker error','Redis monitor failed','NOPERM','SEVERE']};errors['OutOfMemoryError']=count_java_oom(text)
  health=False
  try:
   ip=next(v['IPAddress'] for v in c['NetworkSettings']['Networks'].values() if v['IPAddress'])
   with urllib.request.urlopen('http://'+ip+':8080/api/v1/health',timeout=2) as response:health=json.load(response)['status']=='ok'
  except Exception:pass
  samples.append({'name':name,'running':c['State']['Running'],'health':health,'restarts':c['RestartCount'],'oom_killed':c['State']['OOMKilled'],'pending':s['pending'],'errors':errors,'completed':len(re.findall(r'Redis monitor completed:',text))})
 stats=subprocess.check_output(['docker','stats','--no-stream','--format','{{json .}}',*expected.keys()],text=True)
 usage={x['Name']:{'memory':x['MemUsage'],'cpu':x['CPUPerc']} for x in map(json.loads,stats.splitlines())}
 for sample in samples:
  sample.update(usage.get(sample['name'],{}))
  if not sample['health'] or sample['restarts'] or sample['oom_killed'] or any(sample['errors'].values()) or len(sample['pending'])>1:
   alert={'at':utc(),'worker':sample['name'],'reason':'Health, restart, error or concurrency check failed'}
   if not any(a['worker']==alert['worker'] for a in summary['alerts']):summary['alerts'].append(alert)
 entries=r.cmd('XRANGE',e.get('KAFKA_RESPONSE_TOPIC','panako.monitor.response'),'('+cursor,'+','COUNT',1000)
 for eid,fields in entries:
  cursor=eid;f=dict(zip(fields[::2],fields[1::2]))
  try:result=json.loads(f.get('value',f.get('payload','{}')))
  except ValueError:continue
  rid=result.get('recording_id',f.get('key'))
  if rid in seen:responses[eid]={'worker':seen[rid],**{k:result.get(k) for k in ['recording_id','status','processing_time_ms','duration_seconds','unique_tracks_count']}}
 summary.update(last_at=utc(),elapsed_seconds=round(time.monotonic()-start),workers=samples,responses=list(responses.values()))
 tmp=root/'watch.tmp';tmp.write_text(json.dumps(summary,indent=2));tmp.replace(root/'watch.json')
 with (root/'samples.jsonl').open('a') as out:out.write(json.dumps({'at':summary['last_at'],'workers':samples})+'\n')
 if any(not x['running'] or x['restarts'] or x['oom_killed'] or x['errors'].get('OutOfMemoryError') for x in samples):
  summary['fatal']=True;break
 time.sleep(15)
summary['finished_at']=utc();(root/'watch.json').write_text(json.dumps(summary,indent=2))
