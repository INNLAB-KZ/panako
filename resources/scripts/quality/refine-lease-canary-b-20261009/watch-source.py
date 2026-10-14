import pathlib,json,subprocess,time,datetime,os
from redis_state import Redis,env,pairs
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-lease-canary-b-20261009');d=json.loads((p/'status.json').read_text());assert d['phase']=='running';c=json.loads(subprocess.check_output(['docker','inspect',d['name']]))[0];r=Redis(env(c));tracked={}
for row in d['pending_after_stop']:
 values=r.cmd('XRANGE','panako.monitor.refine.request',row[0],row[0]);rid=None
 if values:
  f=pairs(values[0][1]);b=json.loads(f.get('value',f.get('payload','{}')));rid=b.get('recording_id',f.get('key'))
 tracked[row[0]]={'recording_id':rid,'responses':[]}
cursor=str(int(datetime.datetime.fromisoformat(d['source_stopped_at']).timestamp()*1000))+'-0';end=time.monotonic()+2400
while True:
 for mid,item in tracked.items():item['pending']=r.cmd('XPENDING','panako.monitor.refine.request','panako-worker-refine',mid,mid,1)
 if tracked:
  for eid,v in r.cmd('XRANGE','panako.monitor.refine.response','('+cursor,'+','COUNT',1000):
   cursor=eid;f=pairs(v)
   try:b=json.loads(f.get('value',f.get('payload','{}')))
   except Exception:continue
   for item in tracked.values():
    if item['recording_id'] and b.get('recording_id',f.get('key'))==item['recording_id']:item['responses'].append({'id':eid,'status':b.get('status'),'processing_time_ms':b.get('processing_time_ms')})
 done=all(not x['pending'] and any(v['status']=='ok' for v in x['responses']) for x in tracked.values());out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'tracked':tracked,'all_recovered':done};tmp=p/'recovery.tmp';tmp.write_text(json.dumps(out,indent=2));tmp.replace(p/'recovery.json')
 if done or time.monotonic()>end:break
 time.sleep(10)
