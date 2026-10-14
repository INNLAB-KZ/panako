import json,pathlib,subprocess,time,datetime,re,os
from redis_state import Redis,env,pairs
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-lease-canary-20261009');d=json.loads((p/'status.json').read_text());c=json.loads(subprocess.check_output(['docker','inspect',d['name']]))[0];r=Redis(env(c));owner=c['Config']['Hostname']+'-1-1';stream='panako.monitor.refine.request';group='panako-worker-refine';seen={};responses={};cursor=str(int(datetime.datetime.fromisoformat(d['started_at']).timestamp()*1000))+'-0';end=time.monotonic()+2400
while time.monotonic()<end:
 pending=r.cmd('XPENDING',stream,group,'-','+',10,owner)
 for row in pending:
  rows=r.cmd('XRANGE',stream,row[0],row[0])
  if not rows:continue
  f=pairs(rows[0][1]);body=json.loads(f.get('value',f.get('payload','{}')));rid=body.get('recording_id',f.get('key'));segments=body.get('segments') or [];span=sum(max(0,float(x.get('end_seconds',0))-float(x.get('start_seconds',0))) for x in segments)
  if rid not in seen:seen[rid]={'message_id':row[0],'segment_count':len(segments),'segment_seconds':span,'deliveries':row[3]}
 logs=subprocess.check_output(['docker','logs','--since',d['started_at'],'--tail','50000',c['Id']],stderr=subprocess.STDOUT,text=True);own_ms=set(map(int,re.findall(r'Redis monitor completed: .*? in (\d+)ms',logs)))
 for eid,fields in r.cmd('XRANGE','panako.monitor.refine.response','('+cursor,'+','COUNT',1000):
  cursor=eid;f=pairs(fields)
  try:result=json.loads(f.get('value',f.get('payload','{}')))
  except (ValueError,TypeError):continue
  rid=result.get('recording_id',f.get('key'))
  if rid in seen:responses[eid]={'recording_id':rid,**seen[rid],**{k:result.get(k) for k in ['status','processing_time_ms','duration_seconds','unique_tracks_count']}}
 for row in responses.values():row['matched_own_completion']=row.get('processing_time_ms') in own_ms
 output={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'pending_count':len(pending),'lease_samples':[{'id':v[0],'owner':v[1],'idle_ms':v[2],'deliveries':v[3]} for v in pending],'seen':seen,'responses':list(responses.values())};tmp=p/'tasks.tmp';tmp.write_text(json.dumps(output,indent=2));tmp.replace(p/'tasks.json');
 with (p/'leases.jsonl').open('a') as f:f.write(json.dumps({'at':output['at'],'pending':output['lease_samples']})+'\n')
 time.sleep(5)
