"""Read-only stream timing and worker resource snapshot; no payloads/secrets emitted."""
import datetime,json,pathlib,statistics,subprocess,sys,time
sys.path.insert(0,'/home/bekzat/isolated-pair-20261009')
from redis_state import Redis,env,pairs

def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
def stats(values):
    values=sorted(values)
    return {'n':len(values),'mean':round(statistics.mean(values),2),'p95':values[min(len(values)-1,int(len(values)*.95))],'max':max(values)} if values else {'n':0}

now=int(time.time()*1000);cutoff=now-86400000
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'roles':{}}
for role in ['stage1','refine']:
    c=inspect('panako-isolated-pair-'+role+'-1');e=env(c);r=Redis(e)
    assert e['REDIS_HOST']=='10.0.0.4'
    stream='panako.monitor.'+('refine.' if role=='refine' else '')
    req={};responses=[]
    for mid,fields in r.cmd('XRANGE',stream+'request','-','+','COUNT',5000):
        b=json.loads(pairs(fields)['value'])
        req.setdefault(b['recording_id'],[]).append({'id':mid,'segments_seconds':sum(float(z['end_seconds'])-float(z['start_seconds']) for z in b.get('segments',[]) or []),'segments':len(b.get('segments',[]) or [])})
    for mid,fields in r.cmd('XRANGE',stream+'response',str(cutoff)+'-0','+','COUNT',5000):
        b=json.loads(pairs(fields)['value']);rid=b['recording_id']
        q=next((q for q in reversed(req.get(rid,[])) if int(q['id'].split('-')[0])<=int(mid.split('-')[0])),None)
        responses.append({'id':mid,'recording_id':rid,'status':b.get('status'),'duration_seconds':b.get('duration_seconds'),'processing_time_ms':b.get('processing_time_ms'),'request':q,'request_to_response_ms':int(mid.split('-')[0])-int(q['id'].split('-')[0]) if q else None})
    hour=[x for x in responses if x['status']=='ok' and 3300<=float(x['duration_seconds'] or 0)<=3900 and (role=='stage1' or x['request'] and x['request']['segments_seconds']>=3300)]
    out['roles'][role]={'responses_last24h':len(responses),'errors':[x for x in responses if x['status']!='ok'],'hour_processing_ms':stats([x['processing_time_ms'] for x in hour]),'hour_request_to_response_ms':stats([x['request_to_response_ms'] for x in hour if x['request_to_response_ms'] is not None]),'groups':{s:[pairs(g) for g in r.cmd('XINFO','GROUPS',stream+s)] for s in ['request','response']},'samples':responses,'running':c['State']['Running'],'oom':c['State']['OOMKilled'],'restarts':c['RestartCount']}
print(json.dumps(out))
