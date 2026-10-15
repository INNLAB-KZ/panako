"""Read-only query and replication snapshot, run on Main using existing credentials."""
import base64, datetime, json, subprocess, urllib.request

c = json.loads(subprocess.check_output(['docker', 'inspect', 'project-panako-store-h-1']))[0]
e = dict(x.split('=', 1) for x in c['Config']['Env'])
auth = 'Basic ' + base64.b64encode((e['CLICKHOUSE_USER'] + ':' + e['CLICKHOUSE_PASSWORD']).encode()).decode()
queries = {
    'replicas': 'SELECT table,is_readonly,is_session_expired,queue_size,absolute_delay,active_replicas,total_replicas FROM system.replicas FORMAT JSONEachRow',
    'queries_15min': "SELECT toString(address) AS client,count() AS n,round(avg(query_duration_ms),1) AS mean_ms,quantileExact(0.95)(query_duration_ms) AS p95_ms,max(query_duration_ms) AS max_ms FROM system.query_log WHERE event_date>=today()-1 AND event_time>now()-INTERVAL 15 MINUTE AND type=2 AND query_kind='Select' AND has(tables,'default.olaf_fingerprints') GROUP BY address SETTINGS max_threads=2,max_memory_usage=536870912 FORMAT JSONEachRow",
    'errors_15min': 'SELECT exception_code,count() AS n FROM system.query_log WHERE event_date>=today()-1 AND event_time>now()-INTERVAL 15 MINUTE AND type IN (3,4) GROUP BY exception_code SETTINGS max_threads=2,max_memory_usage=536870912 FORMAT JSONEachRow'}
out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'hosts': {}}
for host in ['10.0.0.12', '10.0.0.10']:
    out['hosts'][host] = {}
    for name, query in queries.items():
        with urllib.request.urlopen(urllib.request.Request('http://' + host + ':8123/', data=query.encode(), headers={'Authorization': auth}), timeout=20) as response:
            out['hosts'][host][name] = [json.loads(line) for line in response.read().decode().splitlines()]
print(json.dumps(out))
