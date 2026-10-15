import pathlib,json,subprocess,time,datetime,urllib.request,re,os,sys
from redis_state import Redis,env,pairs
os.umask(0o077)
p=pathlib.Path(sys.argv[1])
d=json.loads((p/'status.json').read_text());assert d['phase']=='running'
c=json.loads(subprocess.check_output(['docker','inspect',d['new'][0]['id']]))[0]
r=Redis(env(c)); tracked={}
for row in {x[0]:x for x in d['pending_before_stop']+d['pending_after_stop']}.values():
    values=r.cmd('XRANGE','panako.monitor.refine.request',row[0],row[0]);rid=None
    if values:
        f=pairs(values[0][1]);b=json.loads(f.get('value',f.get('payload','{}')));rid=b.get('recording_id',f.get('key'))
    tracked[row[0]]={'recording_id':rid,'responses':[]}
cursor=str(int(datetime.datetime.fromisoformat(d['started_at']).timestamp()*1000))+'-0'
start=time.monotonic();out={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'alerts':[],'min_available_gib':1000}
def snapshot():
    cpu=list(map(int,pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))
    network={}
    for device in pathlib.Path('/sys/class/net').iterdir():
        if device.name=='lo' or device.name.startswith(('veth','docker','br-')):continue
        network[device.name]={k:int((device/'statistics'/k).read_text()) for k in ['rx_bytes','tx_bytes','rx_errors','tx_errors','rx_dropped','tx_dropped']}
    return time.monotonic(),cpu,network
previous=snapshot()
while True:
    current=snapshot();duration=current[0]-previous[0];diff=[b-a for a,b in zip(previous[1],current[1])]
    resource={'cpu_busy_pct':100*(sum(diff)-diff[3]-diff[4])/sum(diff) if sum(diff) else 0,'steal_pct':100*diff[7]/sum(diff) if sum(diff) else 0,'network':{}}
    for device,values in current[2].items():
        delta={k:v-previous[2][device][k] for k,v in values.items()}
        resource['network'][device]={**delta,'rx_mbps':delta['rx_bytes']*8/duration/1e6,'tx_mbps':delta['tx_bytes']*8/duration/1e6}
    previous=current
    rows=[]
    for item in d['new']:
        c=json.loads(subprocess.check_output(['docker','inspect',item['id']]))[0]
        logs=subprocess.check_output(['docker','logs','--since',d['source_stopped_at'],'--tail','50000',item['id']],stderr=subprocess.STDOUT).decode(errors='replace')
        errors={k:logs.count(k) for k in ['Redis monitor failed','Redis worker error','Redis task ownership lost','Redis task not acknowledged','Redis task heartbeat failed','java.lang.OutOfMemoryError','SocketTimeoutException','Window processing failed']}
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
    out['resources']=resource
    out.update(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),elapsed_seconds=round(elapsed),containers=rows,available_gib=round(available,2),tracked=tracked,all_recovered=recovered)
    done=elapsed>=2400 or (elapsed>=900 and recovered and all(len(x['processing_ms'])>=2 for x in rows))
    if done:out.update(finished=True,passed=not out['alerts'] and recovered and all(x['processing_ms'] for x in rows))
    tmp=p/'watch.tmp';tmp.write_text(json.dumps(out,indent=2));tmp.replace(p/'watch.json')
    with (p/'resource-observations.jsonl').open('a') as history:history.write(json.dumps({'at':out['at'],'available_gib':available,**resource})+'\n')
    if done:break
    time.sleep(15)
