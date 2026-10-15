import subprocess,json,urllib.request,base64,datetime,pathlib,os,sys
os.umask(0o077)
p=pathlib.Path('/home/bekzat/replica-registration-retire-20261007');p.mkdir(exist_ok=True)
c=json.loads(subprocess.check_output(['docker','inspect','project-panako-store-h-1']))[0]
e=dict(v.split('=',1) for v in c['Config']['Env'])
auth='Basic '+base64.b64encode((e['CLICKHOUSE_USER']+':'+e['CLICKHOUSE_PASSWORD']).encode()).decode()
def q(sql):
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8123/',data=sql.encode(),headers={'Authorization':auth}),timeout=60) as r:return r.read().decode()
def rows(sql):return [json.loads(v) for v in q(sql+' FORMAT JSONEachRow').splitlines()]
names=sys.argv[1:];assert names and set(names)<=set(['ch03','ch04','ch08'])
tables=['olaf_fingerprints','olaf_metadata']
for name in names:
 for table in tables:
  state=rows("SELECT replica_name,replica_is_active,is_readonly,is_session_expired,active_replicas,zookeeper_path FROM system.replicas WHERE database='default' AND table='"+table+"'")
  assert len(state)==1
  s=state[0];assert s['replica_name']=='ch01' and not s['is_readonly'] and not s['is_session_expired'] and s['active_replicas']>=5
  assert name in s['replica_is_active'] and s['replica_is_active'][name]==0
  root=s['zookeeper_path']+'/replicas/'+name
  backup=rows("SELECT name,value,path FROM system.zookeeper WHERE path='"+root+"'")
  (p/(name+'-'+table+'-before.json')).write_text(json.dumps({'state':state,'registration_children':backup}))
  q("SYSTEM DROP REPLICA '"+name+"' FROM TABLE default."+table)
  after=rows("SELECT replica_is_active,total_replicas,active_replicas FROM system.replicas WHERE database='default' AND table='"+table+"'")
  assert name not in after[0]['replica_is_active']
  result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'replica':name,'table':table,'after':after}
  (p/(name+'-'+table+'-result.json')).write_text(json.dumps(result));print(json.dumps(result),flush=True)
