import sys,json,time,subprocess,statistics
sys.path.insert(0,'/home/bekzat/jdbc-rollout-20261004')
from redis_state import Redis,env,pairs
c=json.loads(subprocess.check_output(['docker','inspect','project-panako-api-memory-1']))[0];r=Redis(env(c));out={'epoch':time.time()}
for k,stream,group in [('stage1','panako.monitor.request','panako-workers'),('refine','panako.monitor.refine.request','panako-worker-refine')]:
 g=next(pairs(x) for x in r.cmd('XINFO','GROUPS',stream) if pairs(x)['name']==group);s=pairs(r.cmd('XINFO','STREAM',stream));out[k]={x:g.get(x) for x in ['entries-read','lag','pending']};out[k]['entries-added']=s.get('entries-added')
rows=r.cmd('XRANGE','panako.monitor.response',str(int((time.time()-3600)*1000))+'-0','+','COUNT',10000);ts=[];statuses={};total=0
for _,fields in rows:
 f=pairs(fields)
 try:d=json.loads(f.get('value',f.get('payload','{}')))
 except (ValueError,TypeError):continue
 total+=1;status=str(d.get('status'));statuses[status]=statuses.get(status,0)+1
 duration=d.get('duration_seconds');ms=d.get('processing_time_ms')
 if isinstance(duration,(int,float)) and 3300<=duration<=3900 and isinstance(ms,(int,float)):ts.append(ms/1000)
out['stage1_last_hour']={'responses':total,'statuses':statuses,'hourly_count':len(ts),'mean_seconds':round(statistics.mean(ts),2) if ts else None,'max_seconds':max(ts) if ts else None,'over_480':sum(x>480 for x in ts),'over_600':sum(x>600 for x in ts)}
print(json.dumps(out))
