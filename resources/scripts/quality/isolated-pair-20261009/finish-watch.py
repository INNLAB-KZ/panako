"""Read-only finite observation until both request and response queues drain."""
import datetime, json, os, pathlib, re, statistics, subprocess, sys, time, urllib.request
sys.path.insert(0,'/home/bekzat/isolated-pair-20261009')
from redis_state import Redis, env, pairs

os.umask(0o077)
p=pathlib.Path('/home/bekzat/isolated-pair-finish-20261010');p.mkdir(mode=0o700,exist_ok=True)
deployment=json.loads(pathlib.Path('/home/bekzat/isolated-pair-20261009/status.json').read_text())
def inspect(n):return json.loads(subprocess.check_output(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def eid(s):return tuple(map(int,s.split('-')))
cs={item['role']:inspect(item['id']) for item in deployment['services']}
r=Redis(env(cs['stage1']));assert env(cs['stage1'])['REDIS_HOST']=='10.0.0.4'
streams=['panako.monitor.request','panako.monitor.response','panako.monitor.refine.request','panako.monitor.refine.response']
cursors={s:'-' for s in streams};records={s:{} for s in streams}
start=time.monotonic();started_at=utc();quiet_since=None
if (p/'watch.json').exists():
    started_at=json.loads((p/'watch.json').read_text())['started_at']
def hardware():
    cpu=list(map(int,pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))
    network={k:int((pathlib.Path('/sys/class/net/enp7s0/statistics')/k).read_text()) for k in ['rx_bytes','tx_bytes','rx_errors','tx_errors','rx_dropped','tx_dropped']}
    return time.monotonic(),cpu,network
previous=hardware()
while time.monotonic()-start<21600:
    groups={};alerts=[];resolved_warnings=[]
    for stream in streams:
        if not r.cmd('EXISTS',stream):groups[stream]=[];continue
        groups[stream]=[pairs(x) for x in r.cmd('XINFO','GROUPS',stream)]
        for _ in range(20):
            rows=r.cmd('XRANGE',stream,cursors[stream],'+','COUNT',250)
            for mid,raw in rows:
                fields=pairs(raw)
                try:b=json.loads(fields.get('value',fields.get('payload','{}')))
                except (ValueError,TypeError):alerts.append({'invalid_json':stream,'message_id':mid});continue
                if not isinstance(b,dict):alerts.append({'invalid_body':stream,'message_id':mid});continue
                records[stream][mid]={'message_id':mid,'recording_id':b.get('recording_id',fields.get('key')),
                                     'status':b.get('status'),'duration_seconds':b.get('duration_seconds'),
                                     'processing_time_ms':b.get('processing_time_ms'),
                                     'segment_seconds':sum(max(0,float(x.get('end_seconds',0))-float(x.get('start_seconds',0))) for x in b.get('segments',[]) or [])}
                cursors[stream]='('+mid
            if len(rows)<250:break
        else:raise RuntimeError('Stream scan exceeded safe bound')
    roles={}
    for item in deployment['services']:
        role=item['role'];c=inspect(item['id']);e=env(c)
        assert e['REDIS_HOST']=='10.0.0.4' and e['CLICKHOUSE_HOST']=='10.0.1.2'
        request=item['stream'];response=request.replace('.request','.response')
        group=next(x for x in groups[request] if x['name']==item['group'])
        pending=r.cmd('XPENDING',request,item['group'],'-','+',100)
        latest={};by_recording={}
        for row in records[response].values():
            latest[row['recording_id']]=row
            by_recording.setdefault(row['recording_id'],[]).append(row)
        missing=[];successful=[];used=set()
        for row in records[request].values():
            result=next((x for x in by_recording.get(row['recording_id'],[]) if x['message_id'] not in used and eid(x['message_id'])>=eid(row['message_id'])),None)
            if result:used.add(result['message_id'])
            if result and eid(result['message_id'])>=eid(row['message_id']) and result['status']=='ok':successful.append(result)
            else:missing.append({'message_id':row['message_id'],'recording_id':row['recording_id'],'response_status':result['status'] if result else None})
        errors=[x for x in latest.values() if x['status']!='ok']
        if errors:alerts.append({'role':role,'error_responses':errors})
        logs=subprocess.check_output(['docker','logs','--since',started_at,'--tail','10000',item['id']],stderr=subprocess.STDOUT,text=True)
        failures={k:logs.count(k) for k in ['Redis monitor failed','Redis worker error','Redis read loop error','Redis task heartbeat failed','Redis task ownership lost','Redis task not acknowledged','java.lang.OutOfMemoryError','Window processing failed','SocketTimeoutException']}
        # Keep ownership warnings visible, but do not block queue acceptance for
        # a request with a distinct successful response and a completed ACK.
        # Unknown, still-pending or failed requests remain alerts.
        ownership_ids=re.findall(r'Redis task ownership lost for '+re.escape(request)+r':(\d+-\d+)',logs)
        successful_request_ids=set(records[request])-{x['message_id'] for x in missing}
        for mid in ownership_ids:
            if mid in successful_request_ids and group['consumers']==1 and not r.cmd('XPENDING',request,item['group'],mid,mid,1):
                resolved_warnings.append({'role':role,'message_id':mid,'warning':'Redis task ownership lost',
                                          'resolution':'Distinct successful response present; request no longer pending; one consumer'})
                failures['Redis task ownership lost']-=1
        try:
            with urllib.request.urlopen('http://127.0.0.1:'+str(item['port'])+'/api/v1/health',timeout=3) as result:healthy=json.load(result).get('status')=='ok'
        except Exception:healthy=False
        if not healthy or not c['State']['Running'] or c['RestartCount'] or c['State']['OOMKilled'] or any(failures.values()):
            alerts.append({'role':role,'health':healthy,'restarts':c['RestartCount'],'oom':c['State']['OOMKilled'],'errors':failures})
        completed=list({x['message_id']:x for x in successful}.values())
        durations=[x['processing_time_ms'] for x in completed if isinstance(x['processing_time_ms'],(int,float))]
        hour=[x['processing_time_ms'] for x in completed if isinstance(x['processing_time_ms'],(int,float)) and 3300<=float(x['duration_seconds'] or 0)<=3900
              and (role=='stage1' or any(req['recording_id']==x['recording_id'] and req['segment_seconds']>=3300 for req in records[request].values()))]
        consumers=groups[response]
        response_drained=bool(consumers) and all(g['lag']==0 and g['pending']==0 for g in consumers)
        roles[role]={'requests':len(records[request]),'successful':len(successful),'missing':missing,'pending':pending,'group':group,
                     'response_groups':consumers,'response_drained':response_drained,'healthy':healthy,'errors':failures,
                     'processing_mean_ms':round(statistics.mean(durations)) if durations else None,
                     'hour_count':len(hour),'hour_mean_ms':round(statistics.mean(hour)) if hour else None,'hour_max_ms':max(hour,default=0)}
    current=hardware();elapsed=current[0]-previous[0];cpu=[b-a for a,b in zip(previous[1],current[1])]
    net={k:v-previous[2][k] for k,v in current[2].items()};previous=current
    mem={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    resource={'cpu_busy_pct':round(100*(sum(cpu)-cpu[3]-cpu[4])/sum(cpu),2) if sum(cpu) else 0,
              'available_gib':round(mem['MemAvailable']/1024**2,2),'private_rx_mbps':round(net['rx_bytes']*8/elapsed/1e6,2),
              'interface_faults':{k:net[k] for k in ['rx_errors','tx_errors','rx_dropped','tx_dropped']}}
    if resource['available_gib']<3:alerts.append({'low_available_gib':resource['available_gib']})
    drained=all(not v['missing'] and v['group']['lag']==0 and v['group']['pending']==0 and v['response_drained'] for v in roles.values())
    if drained and not alerts:
        if quiet_since is None:quiet_since=time.monotonic()
    else:quiet_since=None
    finished=quiet_since is not None and time.monotonic()-quiet_since>=60
    out={'started_at':started_at,'at':utc(),'elapsed_seconds':round(time.monotonic()-start),'roles':roles,'resources':resource,'alerts':alerts,'resolved_warnings':resolved_warnings,'finished':finished,'passed':finished}
    tmp=p/'watch.tmp';tmp.write_text(json.dumps(out,indent=2));tmp.replace(p/'watch.json')
    with (p/'history.jsonl').open('a') as f:f.write(json.dumps(out)+'\n')
    (p/'records.json').write_text(json.dumps(records,indent=2))
    print(json.dumps({'at':out['at'],'stage1':{k:roles['stage1'][k] for k in ['requests','successful','group']},'refine':{k:roles['refine'][k] for k in ['requests','successful','group']},'alerts':len(alerts),'finished':finished}),flush=True)
    if finished:break
    time.sleep(30)
else:raise SystemExit('Observation duration exceeded; inspect queues before continuing')
