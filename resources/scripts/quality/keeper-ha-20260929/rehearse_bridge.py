import pathlib,subprocess,json,time,os,xml.etree.ElementTree as E,datetime
os.umask(0o077);p=pathlib.Path('/home/bekzat/keeper-bridge-test-20260929');p.mkdir(mode=0o700,exist_ok=True)
net='keeper-bridge-test-20260929';parent='keeper-bridge-host-20260929';image='clickhouse/clickhouse-keeper@sha256:76fcf48c7bf75c4a07a86ecb52b451f91a2e35f0fe0e1913ef666eb90db1021e';created=[];report={'phase':'starting','production_untouched':True}
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
 name='keeper-bridge-node-'+str(n)+'-20260929'
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
save()
try:
 assert subprocess.run(['docker','network','inspect',net],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
 docker('network','create',net)
 docker('run','-d','--name',parent,'--network',net,'--network-alias','keeper-main-test','--memory','32m','--restart=no','--entrypoint','/bin/sleep','haproxy:3.0-alpine','3600');created.append(parent)
 config(1,[1],False);legacy=start(1)
 await_client(1,'get /keeper/config',lambda x:'server.1=' in x);client(1,'create /migration_probe retained_value')
 config(1,[1],True);docker('restart',legacy)
 await_client(1,'get /migration_probe',lambda x:'retained_value' in x)
 config(4,[1,4]);bridge=start(4);time.sleep(2)
 client(1,'reconfig add "server.4=keeper-main-test:19234;participant;1"')
 report['two_members']=await_client(1,'get /keeper/config',lambda x:'server.4=' in x);await_client(4,'get /migration_probe',lambda x:'retained_value' in x)
 report['phase']='bridge_joined';save()
 client(4,'reconfig remove "1"')
 report['new_single_member']=await_client(4,'get /keeper/config',lambda x:'server.4=' in x and 'server.1=' not in x)
 docker('stop','--time','3',legacy)
 config(4,[4]);docker('restart',bridge)
 await_client(4,'get /migration_probe',lambda x:'retained_value' in x)
 for n in [2,3]:
  config(n,[4,2,3]);start(n);time.sleep(2)
  client(4,'reconfig add "server.'+str(n)+'='+endpoints[n][0]+':9234;participant;1"')
  report['membership_after_'+str(n)]=await_client(4,'get /keeper/config',lambda x:('server.'+str(n)+'=') in x)
  await_client(n,'get /migration_probe',lambda x:'retained_value' in x)
  report['phase']='joined_'+str(n);save()
 config(4,[4,2,3]);docker('restart',bridge)
 await_client(4,'get /migration_probe',lambda x:'retained_value' in x)
 docker('stop','--time','3',bridge)
 report['read_after_main_keeper_stop']=await_client(2,'get /migration_probe',lambda x:'retained_value' in x)
 client(2,'create /after_failover writable')
 report['write_after_main_keeper_stop']=await_client(3,'get /after_failover',lambda x:'writable' in x)
 report.update(phase='passed',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
except BaseException as ex:report.update(phase='failed',error=type(ex).__name__+': '+str(ex)[:1500])
finally:
 for name in reversed(created):subprocess.run(['docker','stop','--time','3',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 report['containers_stopped']=True;save()
print(json.dumps(report,indent=2))
