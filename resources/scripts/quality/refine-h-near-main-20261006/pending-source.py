import json,subprocess,datetime
from redis_state import Redis,env,pairs
c=json.loads(subprocess.check_output(['docker','inspect','project-panako-store-h-1']))[0];r=Redis(env(c));s='panako.monitor.refine.request';g='panako-worker-refine';rows=[]
for raw in r.cmd('XINFO','CONSUMERS',s,g):
 owner=pairs(raw)['name']
 if not owner.startswith(c['Config']['Hostname']+'-'):continue
 for row in r.cmd('XPENDING',s,g,'-','+',100,owner):
  out={'id':row[0],'owner':row[1],'idle_ms':row[2],'deliveries':row[3]};entries=r.cmd('XRANGE',s,row[0],row[0])
  if entries:
   f=pairs(entries[0][1]);body=json.loads(f.get('value',f.get('payload','{}')));out['recording_id']=body.get('recording_id',f.get('key'))
  rows.append(out)
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'container_id':c['Id'],'running':c['State']['Running'],'pending':rows}))
