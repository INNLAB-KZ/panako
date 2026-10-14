import pathlib,json,subprocess,time,datetime,os
from redis_state import Redis,env,pairs
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-h-disable-20261006');c=json.loads(subprocess.check_output(['docker','inspect','project-panako-store-h-1']))[0];r=Redis(env(c));message='1790879760794-0';recording='01a0dbef-c24a-7ccb-a1b3-4e2126fdd0c1';cursor=str(int(datetime.datetime.fromisoformat('2026-10-06T13:53:45.704787+00:00').timestamp()*1000))+'-0';responses=[];history=[];end=time.monotonic()+1800
while time.monotonic()<end:
 pending=r.cmd('XPENDING','panako.monitor.refine.request','panako-worker-refine',message,message,1)
 for eid,fields in r.cmd('XRANGE','panako.monitor.refine.response','('+cursor,'+','COUNT',1000):
  cursor=eid;f=pairs(fields)
  try:body=json.loads(f.get('value',f.get('payload','{}')))
  except (ValueError,TypeError):continue
  if body.get('recording_id',f.get('key'))==recording:responses.append({'response_id':eid,**{k:body.get(k) for k in ['status','processing_time_ms','duration_seconds']}})
 state={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'message_id':message,'recording_id':recording,'pending':pending,'responses':responses,'finished':False}
 owner=pending[0][1] if pending else None
 if not history or history[-1]['owner']!=owner:history.append({'at':state['at'],'owner':owner})
 state['ownership_history']=history
 if not pending and responses:state['finished']=True;state['outcome']='cleared_with_ok_response' if any(x['status']=='ok' for x in responses) else 'cleared_with_error_response'
 tmp=p/'recovery.tmp';tmp.write_text(json.dumps(state,indent=2));tmp.replace(p/'recovery.json')
 if state['finished']:break
 time.sleep(10)
else:
 state.update(finished=True,outcome='observation_timeout');(p/'recovery.json').write_text(json.dumps(state,indent=2))
