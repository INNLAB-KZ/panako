"""Bounded read-only Main query timing and host counters."""
import base64,datetime,json,pathlib,subprocess,time,urllib.request
c=json.loads(subprocess.check_output(['docker','inspect','project-panako-store-h-1']))[0]
e=dict(x.split('=',1) for x in c['Config']['Env']);auth='Basic '+base64.b64encode((e['CLICKHOUSE_USER']+':'+e['CLICKHOUSE_PASSWORD']).encode()).decode()
queries={
 'fingerprint_queries_15min':"SELECT toString(address) AS client,count() AS n,round(avg(query_duration_ms),1) AS mean_ms,quantileExact(.95)(query_duration_ms) AS p95_ms,max(query_duration_ms) AS max_ms,round(avg(result_bytes)) AS mean_result_bytes,round(avg(ProfileEvents['NetworkSendElapsedMicroseconds'])/1000,1) AS mean_send_ms FROM system.query_log WHERE event_date=today() AND event_time>now()-INTERVAL 15 MINUTE AND type=2 AND query_kind='Select' AND has(tables,'default.olaf_fingerprints') GROUP BY address ORDER BY n DESC",
 'errors_15min':"SELECT exception_code,count() AS n FROM system.query_log WHERE event_date=today() AND event_time>now()-INTERVAL 15 MINUTE AND type IN (3,4) GROUP BY exception_code",
 'replicas':"SELECT table,is_readonly,is_session_expired,queue_size,absolute_delay,active_replicas,total_replicas FROM system.replicas",
 'active':"SELECT count() AS n,round(max(elapsed),2) AS max_elapsed FROM system.processes WHERE query_id!=currentQueryID()"}
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'db':{}}
for port in [8123,8125]:
 out['db'][port]={}
 for name,q in queries.items():
  q+=' SETTINGS max_threads=2,max_memory_usage=536870912,max_execution_time=8 FORMAT JSONEachRow'
  try:
   with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:'+str(port)+'/',data=q.encode(),headers={'Authorization':auth}),timeout=12) as x:out['db'][port][name]=[json.loads(s) for s in x.read().decode().splitlines()]
  except Exception as ex:out['db'][port][name]={'error':type(ex).__name__}
def sample():
 return list(map(int,pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:9])),{n:int((pathlib.Path('/sys/class/net/ens23/statistics')/n).read_text()) for n in ['rx_bytes','tx_bytes','rx_errors','tx_errors','rx_dropped','tx_dropped']}
a,an=sample();start=time.monotonic();time.sleep(10);b,bn=sample();elapsed=time.monotonic()-start;d=[y-x for x,y in zip(a,b)]
mem={s.split(':')[0]:int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()}
out['host']={'cpu_busy_pct':round(100*(sum(d)-d[3]-d[4])/sum(d),2),'iowait_pct':round(100*d[4]/sum(d),2),'available_gib':round(mem['MemAvailable']/1024**2,2),'rx_mbps':round((bn['rx_bytes']-an['rx_bytes'])*8/elapsed/1e6,2),'tx_mbps':round((bn['tx_bytes']-an['tx_bytes'])*8/elapsed/1e6,2),'interface_faults':{k:bn[k]-an[k] for k in ['rx_errors','tx_errors','rx_dropped','tx_dropped']}}
print(json.dumps(out))
