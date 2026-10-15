import subprocess,json,urllib.request,base64,datetime
c=json.loads(subprocess.check_output(['docker','inspect','project-panako-store-h-1']))[0];e=dict(v.split('=',1) for v in c['Config']['Env']);auth='Basic '+base64.b64encode((e['CLICKHOUSE_USER']+':'+e['CLICKHOUSE_PASSWORD']).encode()).decode()
queries={
'mutations':"SELECT table,count() AS unfinished,sum(parts_to_do) AS parts_to_do,countIf(latest_fail_reason!='') AS errors FROM system.mutations WHERE NOT is_done GROUP BY table FORMAT JSONEachRow",
'store_queries':"SELECT query_start_time,event_time,type,exception_code,query_kind,query_duration_ms FROM system.query_log WHERE event_date=today() AND event_time>now()-INTERVAL 15 MINUTE AND toString(address)='::ffff:10.0.0.19' AND query_kind IN ('Alter','Insert') ORDER BY event_time DESC LIMIT 15 FORMAT JSONEachRow",
'settings':"SELECT name,value,changed FROM system.settings WHERE name IN ('mutations_sync','alter_sync','replication_wait_for_inactive_replica_timeout') FORMAT JSONEachRow",
'replica_activity':"SELECT database,table,replica_is_active FROM system.replicas FORMAT JSONEachRow",
'queue_details':"SELECT database,table,type,is_currently_executing,num_tries,length(last_exception)>0 AS has_error,dateDiff('second',create_time,now()) AS age_seconds FROM system.replication_queue FORMAT JSONEachRow",
'replicas':"SELECT database,table,zookeeper_path,replica_name,is_readonly,is_session_expired,queue_size,absolute_delay,total_replicas,active_replicas FROM system.replicas FORMAT JSONEachRow",
'distributed':"SELECT database,name FROM system.tables WHERE engine='Distributed' FORMAT JSONEachRow",
'cluster_target':"SELECT cluster,host_name,toString(host_address) AS address FROM system.clusters WHERE toString(host_address) IN ('10.0.0.23','91.99.52.184','10.0.0.21','167.233.105.218') FORMAT JSONEachRow",
'processes':"SELECT user,toString(address) AS client,elapsed FROM system.processes WHERE query_id!=currentQueryID() FORMAT JSONEachRow"}
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'hosts':{}}
for host in ['10.0.0.22:8123','10.0.0.12:8123','10.0.0.10:8123','127.0.0.1:8123','127.0.0.1:8125']:
 result={};out['hosts'][host]=result
 for key,q in queries.items():
  try:
   with urllib.request.urlopen(urllib.request.Request('http://'+host+'/',data=q.encode(),headers={'Authorization':auth}),timeout=20) as r:result[key]=[json.loads(v) for v in r.read().decode().splitlines()]
  except Exception as ex:result[key]={'error':type(ex).__name__}
 if host in ['10.0.0.21:8123','10.0.0.23:8123']:
  q="SELECT user,toString(address) AS client,query_kind,arrayStringConcat(tables,',') AS tables_used,count() AS count,max(event_time) AS last_seen FROM system.query_log WHERE event_date>=today()-1 AND event_time>now()-INTERVAL 24 HOUR AND type=2 GROUP BY user,address,query_kind,tables_used ORDER BY count DESC LIMIT 40 SETTINGS max_threads=2,max_memory_usage=536870912 FORMAT JSONEachRow"
  try:
   with urllib.request.urlopen(urllib.request.Request('http://'+host+'/',data=q.encode(),headers={'Authorization':auth}),timeout=20) as r:result['clients_last24h']=[json.loads(v) for v in r.read().decode().splitlines()]
  except Exception as ex:result['clients_last24h']={'error':type(ex).__name__}
print(json.dumps(out))
