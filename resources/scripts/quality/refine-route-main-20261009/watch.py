import pathlib,json,subprocess,time,datetime,urllib.request,re,os,sys
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path(sys.argv[1])
d=json.loads((p/'status.json').read_text());assert d['phase']=='running'
c=json.loads(subprocess.check_output(['docker','inspect',d['new'][0]['id']]))[0]
r=Redis(env(c)); tracked={}
for row in d['pending_after_stop']:
    values=r.cmd('XRANGE','panako.monitor.refine.request',row[0],row[0]);rid=None
    if values:
        f=pairs(values[0][1]);b=json.loads(f.get('value',f.get('payload','{}')));rid=b.get('recording_id',f.get('key'))
    tracked[row[0]]={'recording_id':rid,'responses':[]}
cursor=str(int(datetime.datetime.fromisoformat(d['started_at']).timestamp()*1000))+'-0'
start=time.monotonic();out={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'alerts':[],'min_available_gib':1000}
while True:
    rows=[]
    for item in d['new']:
        c=json.loads(subprocess.check_output(['docker','inspect',item['id']]))[0]
        logs=subprocess.check_output(['docker','logs','--since',d['source_stopped_at'],'--tail','50000',item['id']],stderr=subprocess.STDOUT).decode(errors='replace')
        errors={k:logs.count(k) for k in ['Redis monitor failed','Redis worker error','Redis task ownership lost','java.lang.OutOfMemoryError','SocketTimeoutException','Window processing failed']}
        times=[int(x) for x in re.findall(r'Redis monitor completed: .*? in (\d+)ms',logs)]
        try:
            with urllib.request.urlopen('http://127.0.0.1:'+str(item['port'])+'/api/v1/health',timeout=3) as response:health=json.load(response).get('status')=='ok'
        except Exception:health=False
        row={'name':item['name'],'health':health,'restarts':c['RestartCount'],'oom':c['State']['OOMKilled'],'errors':errors,'processing_ms':times}
        rows.append(row)
        if not health or c['RestartCount'] or c['State']['OOMKilled'] or any(errors.values()):out['alerts'].append(row)
    mem={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()};available=mem['MemAvailable']/1024**2
    out['min_available_gib']=min(out['min_available_gib'],available)
    if available<3:out['alerts'].append({'low_available_gib':available})
    for mid,item in tracked.items():item['pending']=r.cmd('XPENDING','panako.monitor.refine.request','panako-worker-refine',mid,mid,1)
    if tracked:
        for eid,v in r.cmd('XRANGE','panako.monitor.refine.response','('+cursor,'+','COUNT',1000):
            cursor=eid;f=pairs(v)
            try:b=json.loads(f.get('value',f.get('payload','{}')))
            except Exception:continue
            for item in tracked.values():
                if item['recording_id'] and b.get('recording_id',f.get('key'))==item['recording_id']:item['responses'].append({'id':eid,'status':b.get('status'),'processing_time_ms':b.get('processing_time_ms')})
    recovered=all(not x['pending'] and any(v['status']=='ok' for v in x['responses']) for x in tracked.values())
    elapsed=time.monotonic()-start
    out.update(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),elapsed_seconds=round(elapsed),containers=rows,available_gib=round(available,2),tracked=tracked,all_recovered=recovered)
    done=elapsed>=2400 or (elapsed>=1800 and recovered)
    if done:out.update(finished=True,passed=not out['alerts'] and recovered and all(x['processing_ms'] for x in rows))
    tmp=p/'watch.tmp';tmp.write_text(json.dumps(out,indent=2));tmp.replace(p/'watch.json')
    if done:break
    time.sleep(15)
