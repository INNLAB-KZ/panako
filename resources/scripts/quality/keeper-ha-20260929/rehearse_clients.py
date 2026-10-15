import pathlib,subprocess,json,time,os,xml.etree.ElementTree as E,datetime
os.umask(0o077);p=pathlib.Path('/home/bekzat/keeper-client-test-20260929b');p.mkdir(mode=0o700,exist_ok=True)
net='keeper-client-test-20260929b';parent='keeper-client-host-20260929b';image='clickhouse/clickhouse-keeper@sha256:76fcf48c7bf75c4a07a86ecb52b451f91a2e35f0fe0e1913ef666eb90db1021e';created=[];report={'phase':'starting','production_untouched':True}
endpoints={1:('localhost',9234),4:('keeper-main-test',19234),2:('keeper-two-test',9234),3:('keeper-three-test',9234)}
def save():(p/'result.json').write_text(json.dumps(report,indent=2))
def docker(*args):return subprocess.check_output(['docker',*args],text=True,stderr=subprocess.STDOUT).strip()
def config(n,members,enable=True):
 r=E.Element('clickhouse');E.SubElement(r,'listen_host').text='0.0.0.0';log=E.SubElement(r,'logger');E.SubElement(log,'level').text='warning';E.SubElement(log,'console').text='true';k=E.SubElement(r,'keeper_server')
 for key,v in {'tcp_port':str(19181 if n==4 else 9181),'server_id':str(n),'enable_reconfiguration':str(enable).lower(),'hostname_checks_enabled':'false' if 1 in members and len(members)>1 else 'true','log_storage_path':'/var/lib/clickhouse-keeper/log','snapshot_storage_path':'/var/lib/clickhouse-keeper/snapshots'}.items():E.SubElement(k,key).text=v
 raft=E.SubElement(k,'raft_configuration')
 for ident in members:
  s=E.SubElement(raft,'server')
  for key,v in {'id':str(ident),'hostname':endpoints[ident][0],'port':str(endpoints[ident][1])}.items():E.SubElement(s,key).text=v
 E.indent(r);f=p/('keeper'+str(n)+'.xml');f.write_text(E.tostring(r,encoding='unicode'));os.chmod(f,0o644)
def start(n):
 name='keeper-client-node-'+str(n)+'-20260929b'
 assert subprocess.run(['docker','inspect',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
 network=['--network','container:'+parent] if n in [1,4] else ['--network',net,'--network-alias',endpoints[n][0]]
 docker('run','-d','--name',name,*network,'--cpus','.5','--memory','512m','--restart=no','-v',str(p/('keeper'+str(n)+'.xml'))+':/etc/clickhouse-keeper/keeper_config.xml:ro',image);created.append(name);return name
def client(n,query):
 host='keeper-main-test' if n in [1,4] else endpoints[n][0];port=19181 if n==4 else 9181
 return subprocess.check_output(['docker','run','--rm','--network',net,'--entrypoint','clickhouse','clickhouse/clickhouse-server:26.3','keeper-client','--host',host,'--port',str(port),'--query',query],text=True,stderr=subprocess.STDOUT,timeout=12).strip()
def await_client(n,query,condition):
 deadline=time.monotonic()+50;last=''
 while time.monotonic()<deadline:
  try:
   last=client(n,query)
   if condition(last):return last
  except (subprocess.CalledProcessError,subprocess.TimeoutExpired):pass
  time.sleep(1)
 raise RuntimeError('Readiness timeout node '+str(n)+': '+last[:200])

ch_name='keeper-client-ch-20260929b'
def ch(query):
 return subprocess.check_output(['docker','exec',ch_name,'clickhouse-client','--query',query],text=True,stderr=subprocess.STDOUT,timeout=20).strip()
def ch_config(nodes):
 r=E.Element('clickhouse');z=E.SubElement(r,'zookeeper',{'replace':'replace'})
 for host,port in nodes:
  n=E.SubElement(z,'node');E.SubElement(n,'host').text=host;E.SubElement(n,'port').text=str(port)
 E.SubElement(r,'background_schedule_pool_size').text='16'
 E.SubElement(r,'background_pool_size').text='8'
 E.SubElement(r,'background_merges_mutations_concurrency_ratio').text='4'
 E.SubElement(r,'max_server_memory_usage').text='1200000000'
 E.SubElement(r,'mlock_executable').text='false'
 f=p/'ch-config'/'keeper.xml';f.parent.mkdir(exist_ok=True);f.write_text(E.tostring(r,encoding='unicode'));os.chmod(f,0o644);os.chmod(f.parent,0o755)
def ch_ready():
 deadline=time.monotonic()+60
 while time.monotonic()<deadline:
  try:
   if ch('SELECT 1')=='1':return
  except Exception:pass
  time.sleep(1)
 raise RuntimeError('ClickHouse not ready')
def ch_write(stage,value):
 begin=time.monotonic();errors=[]
 while time.monotonic()-begin<65:
  try:
   ch('INSERT INTO rehearsal VALUES ('+str(value)+')')
   if ch('SELECT count() FROM rehearsal WHERE id='+str(value))!='1':raise RuntimeError('marker missing or duplicated')
   state=ch('SELECT is_readonly, is_session_expired, queue_size FROM system.replicas FORMAT JSONEachRow')
   if json.loads(state)['is_readonly']:raise RuntimeError('replica readonly')
   report[stage]={'recovery_seconds':round(time.monotonic()-begin,3),'attempt_errors':errors,'replica_state':state,'connection':ch('SELECT * FROM system.zookeeper_connection FORMAT JSONEachRow')};save();return
  except Exception as ex:errors.append(str(ex)[-600:]);time.sleep(1)
 raise RuntimeError('CH write recovery failed '+stage+': '+str(errors)[-1800:])
def ch_boot():
 ch_config([('keeper-main-test',9181)])
 docker('run','-d','--name',ch_name,'--network',net,'--cpus','1','--memory','2g','--restart=no','-v',str(p/'ch-config')+':/etc/clickhouse-server/config.d:ro','clickhouse/clickhouse-server:26.3');created.append(ch_name)
 ch_ready()
 ch('CREATE TABLE rehearsal (id UInt64) ENGINE=ReplicatedMergeTree(\'/client_rehearsal/table\', \'one\') ORDER BY id')
 report['ch_start']=docker('inspect','--format','{{.Id}} {{.State.StartedAt}}',ch_name)
 report['ch_version']=ch('SELECT version()');ch_write('initial_write',1)

save()
try:
 assert subprocess.run(['docker','network','inspect',net],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
 docker('network','create',net)
 docker('run','-d','--name',parent,'--network',net,'--network-alias','keeper-main-test','--memory','32m','--restart=no','--entrypoint','/bin/sleep','haproxy:3.0-alpine','3600');created.append(parent)
 config(1,[1],False);legacy=start(1)
 await_client(1,'get /keeper/config',lambda x:'server.1=' in x);client(1,'create /migration_probe retained_value')
 ch_boot()
 config(1,[1],True);docker('restart',legacy)
 await_client(1,'get /migration_probe',lambda x:'retained_value' in x)
 config(4,[1,4]);bridge=start(4);time.sleep(2)
 client(1,'reconfig add "server.4=keeper-main-test:19234;participant;1"')
 report['two_members']=await_client(1,'get /keeper/config',lambda x:'server.4=' in x);await_client(4,'get /migration_probe',lambda x:'retained_value' in x)
 report['phase']='bridge_joined';save()
 ch_config([('keeper-main-test',19181)])
 ch('SYSTEM RELOAD CONFIG')
 ch_write('endpoint_reload_before_old_removal',2)
 assert json.loads(report['endpoint_reload_before_old_removal']['connection'])['port']==19181, 'Reload did not move connection before removal'
 client(4,'reconfig remove "1"')
 report['new_single_member']=await_client(4,'get /keeper/config',lambda x:'server.4=' in x and 'server.1=' not in x)
 docker('stop','--time','3',legacy)
 config(4,[4]);docker('restart',bridge)
 await_client(4,'get /migration_probe',lambda x:'retained_value' in x)
 ch_write('after_legacy_removal',3)
 for n in [2,3]:
  config(n,[4,2,3]);start(n);time.sleep(2)
  client(4,'reconfig add "server.'+str(n)+'='+endpoints[n][0]+':9234;participant;1"')
  report['membership_after_'+str(n)]=await_client(4,'get /keeper/config',lambda x:('server.'+str(n)+'=') in x)
  await_client(n,'get /migration_probe',lambda x:'retained_value' in x)
  report['phase']='joined_'+str(n);save()
 config(4,[4,2,3]);docker('restart',bridge)
 await_client(4,'get /migration_probe',lambda x:'retained_value' in x)
 ch_config([('keeper-main-test',19181),('keeper-two-test',9181),('keeper-three-test',9181)])
 ch('SYSTEM RELOAD CONFIG')
 ch_write('final_config_reload',4)
 connected_host=json.loads(report['final_config_reload']['connection'])['host']
 connected_id={'keeper-main-test':4,'keeper-two-test':2,'keeper-three-test':3}[connected_host]
 connected_name='keeper-client-node-'+str(connected_id)+'-20260929b'
 report['stopped_connected_keeper']=connected_id;save()
 docker('stop','--time','3',connected_name)
 ch_write('after_connected_keeper_stop',6)
 assert json.loads(report['after_connected_keeper_stop']['connection'])['host']!=connected_host
 docker('start',connected_name)
 await_client(connected_id,'get /migration_probe',lambda x:'retained_value' in x)
 docker('stop','--time','3',bridge)
 ch_write('after_main_stop',5)
 assert 'keeper-two-test' in report['after_main_stop']['connection'] or 'keeper-three-test' in report['after_main_stop']['connection'], 'Expected remote Keeper connection'
 assert report['ch_start']==docker('inspect','--format','{{.Id}} {{.State.StartedAt}}',ch_name), 'ClickHouse restarted'
 report['clickhouse_not_restarted']=True
 report['read_after_main_keeper_stop']=await_client(2,'get /migration_probe',lambda x:'retained_value' in x)
 client(2,'create /after_failover writable')
 report['write_after_main_keeper_stop']=await_client(3,'get /after_failover',lambda x:'writable' in x)
 report.update(phase='passed',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
except BaseException as ex:report.update(phase='failed',error=type(ex).__name__+': '+str(ex)[:1500])
finally:
 for name in reversed(created):subprocess.run(['docker','stop','--time','3',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 report['containers_stopped']=True;save()
print(json.dumps(report,indent=2))
