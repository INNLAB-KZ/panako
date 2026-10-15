"""Read-only fleet snapshot; invoke over SSH on each refine host."""
import datetime,json,pathlib,re,statistics,subprocess,urllib.request

def run(args):return subprocess.check_output(args,stderr=subprocess.STDOUT,text=True)
ids=run(['docker','ps','-q']).split()
containers=json.loads(run(['docker','inspect',*ids])) if ids else []
result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'refine':[]}
for c in containers:
    env=dict(v.split('=',1) for v in c['Config']['Env'])
    if env.get('PANAKO_WORKER_MODE')!='refine':continue
    logs=run(['docker','logs','--since','1h','--tail','100000',c['Id']])
    times=[int(v) for v in re.findall(r'Redis monitor completed: .*? in (\d+)ms',logs)]
    row={'name':c['Name'],'id':c['Id'],'hostname':c['Config']['Hostname'],'image':c['Image'],
         'jar_sha256':run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0],
         'restarts':c['RestartCount'],'oom':c['State']['OOMKilled'],
         'private_db':env.get('CLICKHOUSE_HOST'),'windows':env.get('MONITOR_PARALLEL_WINDOWS'),
         'redis_threads':env.get('REDIS_WORKER_THREADS'),'heap':env.get('JAVA_TOOL_OPTIONS'),
         'memory_bytes':c['HostConfig']['Memory'],'cpus':c['HostConfig']['NanoCpus']/1e9,
         'completed_in_retained_hour':len(times),'mean_ms':round(statistics.mean(times)) if times else None,
         'max_ms':max(times,default=0),'over10min':sum(t>600000 for t in times),
         'errors':{k:logs.count(k) for k in ['Redis monitor failed','Redis worker error',
             'Redis task ownership lost','Redis task not acknowledged','Redis task heartbeat failed','java.lang.OutOfMemoryError',
             'SocketTimeoutException','Window processing failed']}}
    result['refine'].append(row)
mem={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
result['available_gib']=round(mem['MemAvailable']/1024**2,2)
print(json.dumps(result))
