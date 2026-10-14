"""Read-only task attribution and segment coverage for a rollout cohort."""
import datetime,json,os,pathlib,re,subprocess,sys,time
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path(sys.argv[1]);d=json.loads((p/'status.json').read_text())
cs=[json.loads(subprocess.check_output(['docker','inspect',x['id']]))[0] for x in d['new']]
r=Redis(env(cs[0]));seen={};responses={}
cursor=str(int(datetime.datetime.fromisoformat(d['healthy_at']).timestamp()*1000))+'-0'
stream='panako.monitor.refine.request';group='panako-worker-refine'
end=time.monotonic()+2400
while time.monotonic()<end:
    pending=[];own_times={}
    for c in cs:
        owner=c['Config']['Hostname']+'-1-1'
        for row in r.cmd('XPENDING',stream,group,'-','+',10,owner):
            pending.append({'id':row[0],'owner':row[1],'idle_ms':row[2],'deliveries':row[3]})
            request=r.cmd('XRANGE',stream,row[0],row[0])
            if not request:continue
            f=pairs(request[0][1]);body=json.loads(f.get('value',f.get('payload','{}')));rid=body.get('recording_id',f.get('key'))
            if rid not in seen:
                segments=body.get('segments') or []
                seen[rid]={'recording_id':rid,'message_id':row[0],'container':c['Name'],
                           'segment_seconds':sum(max(0,float(s.get('end_seconds',0))-float(s.get('start_seconds',0))) for s in segments),
                           'segment_count':len(segments),'deliveries':row[3]}
        logs=subprocess.check_output(['docker','logs','--since',d['healthy_at'],'--tail','50000',c['Id']],stderr=subprocess.STDOUT,text=True)
        own_times[c['Name']]=set(map(int,re.findall(r'Redis monitor completed: .*? in (\d+)ms',logs)))
    for eid,raw in r.cmd('XRANGE','panako.monitor.refine.response','('+cursor,'+','COUNT',1000):
        cursor=eid;f=pairs(raw)
        try:b=json.loads(f.get('value',f.get('payload','{}')))
        except (ValueError,TypeError):continue
        rid=b.get('recording_id',f.get('key'))
        if rid in seen:responses[eid]={**seen[rid],**{k:b.get(k) for k in ['status','processing_time_ms','duration_seconds','unique_tracks_count']}}
    for row in responses.values():row['matched_own_completion']=row.get('processing_time_ms') in own_times.get(row['container'],set())
    out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'pending':pending,'seen':seen,'responses':list(responses.values())}
    t=p/'tasks.tmp';t.write_text(json.dumps(out,indent=2));t.replace(p/'tasks.json')
    with (p/'leases.jsonl').open('a') as f:f.write(json.dumps({'at':out['at'],'pending':pending})+'\n')
    time.sleep(10)
