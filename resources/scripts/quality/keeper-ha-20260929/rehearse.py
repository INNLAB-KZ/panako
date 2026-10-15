import pathlib,subprocess,json,time,os,xml.etree.ElementTree as E,datetime
os.umask(0o077);p=pathlib.Path('/home/bekzat/keeper-rehearsal-20260929');p.mkdir(mode=0o700,exist_ok=True)
net='keeper-rehearsal-20260929';image='clickhouse/clickhouse-keeper@sha256:76fcf48c7bf75c4a07a86ecb52b451f91a2e35f0fe0e1913ef666eb90db1021e';created=[]
report={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':'starting','production_untouched':True}
def save():(p/'result.json').write_text(json.dumps(report,indent=2))
def docker(*args):return subprocess.check_output(['docker',*args],text=True,stderr=subprocess.STDOUT).strip()
def config(n,initial=False):
 r=E.Element('clickhouse');E.SubElement(r,'listen_host').text='0.0.0.0';log=E.SubElement(r,'logger');E.SubElement(log,'level').text='warning';E.SubElement(log,'console').text='true'
 k=E.SubElement(r,'keeper_server')
 for key,v in {'tcp_port':'9181','server_id':str(n),'enable_reconfiguration':'false' if initial else 'true','log_storage_path':'/var/lib/clickhouse-keeper/log','snapshot_storage_path':'/var/lib/clickhouse-keeper/snapshots'}.items():E.SubElement(k,key).text=v
 raft=E.SubElement(k,'raft_configuration')
 for ident in ([1] if initial or n==1 else [1,2,3]):
  s=E.SubElement(raft,'server')
  for key,v in {'id':str(ident),'hostname':'localhost' if initial else 'kt'+str(ident),'port':'9234'}.items():E.SubElement(s,key).text=v
 E.indent(r);(p/('keeper'+str(n)+'.xml')).write_text(E.tostring(r,encoding='unicode'));os.chmod(p/('keeper'+str(n)+'.xml'),0o644)
def start(n):
 name='keeper-test-20260929-'+str(n)
 assert subprocess.run(['docker','inspect',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
 docker('run','-d','--name',name,'--hostname','kt'+str(n),'--network',net,'--network-alias','kt'+str(n),'--cpus','.5','--memory','512m','--restart=no','-v',str(p/('keeper'+str(n)+'.xml'))+':/etc/clickhouse-keeper/keeper_config.xml:ro',image);created.append(name)
def client(n,query):
 return subprocess.check_output(['docker','run','--rm','--network',net,'--entrypoint','clickhouse','clickhouse/clickhouse-server:26.3','keeper-client','--host','kt'+str(n),'--port','9181','--query',query],text=True,stderr=subprocess.STDOUT,timeout=15).strip()
def await_client(n,query,condition):
 deadline=time.monotonic()+45;last=''
 while time.monotonic()<deadline:
  try:
   last=client(n,query)
   if condition(last):return last
  except (subprocess.CalledProcessError,subprocess.TimeoutExpired):pass
  time.sleep(1)
 raise RuntimeError('Keeper test readiness timeout for node '+str(n)+': '+last[:200])
save()
try:
 assert subprocess.run(['docker','network','inspect',net],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
 docker('network','create',net)
 config(1,True);start(1)
 report['initial_membership']=await_client(1,'get /keeper/config',lambda x:'server.1=' in x)
 client(1,'create /migration_probe retained_value')
 config(1);docker('restart',created[0])
 report['normalized_membership']=await_client(1,'get /keeper/config',lambda x:'server.1=kt1:9234' in x)
 assert 'retained_value' in client(1,'get /migration_probe');report['phase']='address_normalized';save()
 for n in [2,3]:
  config(n);start(n);time.sleep(2)
  client(1,'reconfig add "server.'+str(n)+'=kt'+str(n)+':9234;participant;1"')
  report['membership_after_'+str(n)]=await_client(1,'get /keeper/config',lambda x:('server.'+str(n)+'=') in x)
  await_client(n,'get /migration_probe',lambda x:'retained_value' in x)
  report['phase']='joined_'+str(n);save()
 docker('stop','--time','5',created[0])
 report['read_after_node1_stop']=await_client(2,'get /migration_probe',lambda x:'retained_value' in x)
 client(2,'create /after_failover writable')
 report['write_after_node1_stop']=await_client(3,'get /after_failover',lambda x:'writable' in x)
 report.update(phase='passed',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
except BaseException as ex:
 report.update(phase='failed',error=type(ex).__name__+': '+str(ex)[:1200])
finally:
 for name in created:subprocess.run(['docker','stop','--time','3',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 report['containers_stopped']=True;save()
print(json.dumps(report,indent=2))
