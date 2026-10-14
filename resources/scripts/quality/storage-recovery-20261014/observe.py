"""Read-only storage acceptance: queue cursor, results, database metadata and runtime."""
import base64, collections, datetime, json, pathlib, re, subprocess, sys, urllib.request
p=pathlib.Path('/home/bekzat/storage-recovery-20261014');sys.path.insert(0,str(p))
from redis_state import Redis,env,pairs
c=json.loads(subprocess.check_output(['docker','inspect','panako-storage-recovery-20261014-store-1']))[0]
e=env(c);r=Redis(e);start=json.loads((p/'started.json').read_text())['at'];ts=int(datetime.datetime.fromisoformat(start).timestamp()*1000)
group=next(pairs(x) for x in r.cmd('XINFO','GROUPS','panako-store-requests') if pairs(x)['name']=='panako-workers')
unread_script="local gs=redis.call('XINFO','GROUPS',KEYS[1]);local id=nil;for _,g in ipairs(gs) do local m={} for i=1,#g,2 do m[g[i]]=g[i+1] end if m.name==ARGV[1] then id=m['last-delivered-id'] end end if not id then return -1 end local p=redis.call('XRANGE',KEYS[1],'-',id,'COUNT',2000);if #p>=2000 then return -1 end return redis.call('XLEN',KEYS[1])-#p"
unread_count=r.cmd('EVAL',unread_script,1,'panako-store-requests','panako-workers')
if unread_count<0: unread_count=None
results=[]
for mid,fields in r.cmd('XRANGE','panako-store-results',str(ts)+'-0','+','COUNT',2000):
    fields=pairs(fields)
    try: row=json.loads(fields.get('value',fields.get('payload','{}')))
    except ValueError:continue
    results.append((mid,row))
latest_by_key={}
for _,row in results:
    key=row.get('request_id',row.get('recording_id'))
    if key: latest_by_key[key]=row.get('status')
logs=subprocess.run(['docker','logs','--since',start,'--tail','5000',c['Id']],capture_output=True,text=True)
log=logs.stdout+logs.stderr;(p/'canary-logs-private.log').write_text(log)
meta=[]
ids=list(dict.fromkeys(int(x['identifier']) for _,x in results if x.get('status')=='ok' and x.get('identifier') is not None))[-12:]
if ids:
    auth='Basic '+base64.b64encode((e['CLICKHOUSE_USER']+':'+e['CLICKHOUSE_PASSWORD']).encode()).decode()
    q='SELECT resource_id,duration,num_fingerprints FROM olaf_metadata FINAL WHERE resource_id IN ('+','.join(map(str,ids))+') SETTINGS max_threads=2,max_execution_time=10 FORMAT JSONEachRow'
    try:
        with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8123/',data=q.encode(),headers={'Authorization':auth}),timeout=15) as resp:meta=[json.loads(x) for x in resp.read().decode().splitlines()]
    except Exception as ex:meta=[{'error':type(ex).__name__}]
summary={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'started_at':start,'group':group,'unread_count':unread_count,
    'results':len(results),'unresolved_error_keys':[k for k,v in latest_by_key.items() if v=='error'],'statuses':dict(collections.Counter(x.get('status','unknown') for _,x in results)),
    'new_store_metadata':meta,'last_result_id':results[-1][0] if results else None,
    'state':{k:c['State'].get(k) for k in ['Running','OOMKilled','StartedAt','ExitCode']},'restarts':c['RestartCount'],
    'store_completions':log.count('Redis store completed:'),'insert_batches':log.count('fingerprints to ClickHouse'),
    'oom':log.count('java.lang.OutOfMemoryError') + log.count('Terminating due to java.lang.OutOfMemoryError'),'severe':log.count('SEVERE'),
    'errors':[{'request_id':x.get('request_id',x.get('recording_id')), 'status':x.get('status')} for _,x in results if x.get('status')=='error'][:10],
    'stats':subprocess.check_output(['docker','stats','--no-stream','--format','{{.MemUsage}} CPU={{.CPUPerc}}',c['Id']],text=True).strip()}
(p/'latest.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
