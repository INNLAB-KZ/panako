import pathlib,subprocess,json,os,urllib.parse,urllib.request,time,socket,hashlib,csv,io,datetime
os.umask(0o077);p=pathlib.Path('/home/bekzat/failover-test-20260929');network='ch-failover-test-20260929';proxy='ch-failover-test';worker='ch-failover-worker-20260929'
e=dict(x.split('=',1) for x in pathlib.Path('/home/bekzat/lookup-bench-20260929/bench.env').read_text().splitlines());a=urllib.parse.parse_qs(urllib.parse.urlsplit(e['OLAF_CLICKHOUSE_URL'][5:]).query)
def query(host,sql):
 args=urllib.parse.urlencode({'user':a['user'][0],'password':a['password'][0],'query':sql})
 with urllib.request.urlopen('http://'+host+':8123/?'+args,timeout=20) as r:return r.read()
def docker(*args):return subprocess.check_output(['docker',*args],text=True).strip()
def control(command):
 with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as s:
  s.settimeout(4);s.connect(str(p/'control/admin.sock'));s.sendall((command+'\n').encode());s.shutdown(socket.SHUT_WR);out=b''
  while True:
   chunk=s.recv(65536)
   if not chunk:break
   out+=chunk
  return out.decode()
report={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':'preflight','production_routes_unchanged':True,'phases':[]}
def save():(p/'result.json').write_text(json.dumps(report,indent=2))
save();created=[]
try:
 for name in [proxy,worker]:assert subprocess.run(['docker','inspect',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
 assert subprocess.run(['docker','network','inspect',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
 hashes=[int(x) for x in query('10.0.1.2','SELECT hash FROM default.olaf_fingerprints ORDER BY hash LIMIT 3 FORMAT TabSeparated').decode().split()]
 sql='SELECT hash,resource_id,t1 FROM default.olaf_fingerprints WHERE hash IN ('+','.join(map(str,hashes))+') ORDER BY hash,resource_id,t1 LIMIT 1000'
 (p/'sample.sql').write_text(sql)
 checks={}
 for host in ['10.0.1.2','10.0.0.12','10.0.0.10']:
  data=query(host,sql+' FORMAT TabSeparated');parts=query(host,"SELECT table,name,hex(hash_of_all_files) AS checksum FROM system.parts WHERE active AND database='default' AND table IN ('olaf_fingerprints','olaf_metadata') ORDER BY table,name FORMAT JSONEachRow")
  checks[host]={'hostname':query(host,'SELECT hostName() FORMAT TabSeparated').decode().strip(),'sample_rows':len(data.splitlines()),'sample_sha256':hashlib.sha256(data).hexdigest(),'parts':[json.loads(x) for x in parts.decode().splitlines()]}
 assert len({x['sample_sha256'] for x in checks.values()})==1
 assert checks['10.0.1.2']['parts']==checks['10.0.0.12']['parts']==checks['10.0.0.10']['parts']
 report['data_checks']=checks;save()
 (p/'control').mkdir(mode=0o700,exist_ok=True)
 image='haproxy@sha256:7ee2f21c5baa25ceb5d01990b78d13a71e8468f5041ac2d69304747d4631407a'
 docker('run','--rm','--user','0:0','-v',str(p/'haproxy.cfg')+':/usr/local/etc/haproxy/haproxy.cfg:ro',image,'haproxy','-c','-f','/usr/local/etc/haproxy/haproxy.cfg')
 docker('network','create',network)
 docker('run','-d','--name',proxy,'--user','0:0','--restart=no','--network',network,'--cpus','.5','--memory','128m','-v',str(p/'haproxy.cfg')+':/usr/local/etc/haproxy/haproxy.cfg:ro','-v',str(p/'control')+':/control',image);created.append(proxy)
 for _ in range(30):
  if (p/'control/admin.sock').exists():break
  time.sleep(.2)
 time.sleep(3)
 env={'OLAF_CLICKHOUSE_URL':e['OLAF_CLICKHOUSE_URL'].replace('10.0.1.2:8123',proxy+':18123'),'JDK_JAVA_OPTIONS':'-Xmx256m -XX:+ExitOnOutOfMemoryError'}
 assert proxy in env['OLAF_CLICKHOUSE_URL']
 (p/'worker.env').write_text(''.join(k+'='+v+'\n' for k,v in env.items()))
 docker('run','-d','--name',worker,'--restart=no','--network',network,'--cpus','1','--memory','512m','--env-file',str(p/'worker.env'),'-v',str(p)+':/test','--entrypoint','java','local/ozen-panako:input-pilot-20260925-5d50209b','-cp','/test/probe.jar:/app/panako.jar','FailoverProbe');created.append(worker)
 def phase(name,commands):
  began=int(time.time()*1000)
  for command in commands:control(command)
  time.sleep(8)
  stats=list(csv.DictReader(io.StringIO(control('show stat').replace('# ', '',1))))
  report['phases'].append({'name':name,'begin_ms':began,'end_ms':int(time.time()*1000),'servers':{r['svname']:r['status'] for r in stats if r['pxname']=='clickhouse_read' and r['svname'] in ['main','replica_a','replica_b']}});report['phase']=name;save()
 phase('main_healthy',[])
 phase('main_unreachable',['set server clickhouse_read/main addr 127.0.0.1 port 1'])
 phase('main_and_replica_a_unreachable',['set server clickhouse_read/replica_a addr 127.0.0.1 port 1'])
 phase('all_unreachable',['set server clickhouse_read/replica_b addr 127.0.0.1 port 1'])
 phase('replicas_recovered',['set server clickhouse_read/replica_a addr 10.0.0.12 port 8123','set server clickhouse_read/replica_b addr 10.0.0.10 port 8123'])
 phase('main_recovered',['set server clickhouse_read/main addr 10.0.1.2 port 8123'])
 (p/'stop').touch();docker('wait',worker)
 logs=subprocess.run(['docker','logs',worker],capture_output=True,text=True);(p/'worker.stdout.log').write_text(logs.stdout);(p/'worker.stderr.log').write_text(logs.stderr)
 rows=[json.loads(l) for l in logs.stdout.splitlines() if l.startswith('{')]
 (p/'probes.json').write_text(json.dumps(rows,indent=2))
 successes=[r for r in rows if r['ok']];assert successes and len({(r['rows'],r['digest']) for r in successes})==1
 for ph in report['phases']:
  stable=[r for r in rows if ph['begin_ms']+3500<=r['at_ms']<=ph['end_ms']]
  ph['successful_probes']=sum(r['ok'] for r in stable);ph['failed_probes']=sum(not r['ok'] for r in stable);ph['hosts']=sorted({r['host'] for r in stable if r['ok']})
  if ph['name']=='all_unreachable':assert ph['successful_probes']==0 and ph['failed_probes']>0
  else:assert ph['successful_probes']>0 and ph['failed_probes']==0
 assert report['phases'][0]['hosts']==[checks['10.0.1.2']['hostname']]
 assert set(report['phases'][1]['hosts'])=={checks['10.0.0.12']['hostname'],checks['10.0.0.10']['hostname']}
 assert report['phases'][2]['hosts']==[checks['10.0.0.10']['hostname']]
 assert report['phases'][-1]['hosts']==[checks['10.0.1.2']['hostname']]
 report.update(phase='passed',result_sets_equal=True,total_probes=len(rows),transition_or_outage_errors=sum(not r['ok'] for r in rows),finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
except BaseException as ex:
 report.update(phase='failed',error=type(ex).__name__+': '+str(ex).split('http')[0])
finally:
 for name in reversed(created):subprocess.run(['docker','stop','--time','3',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 report['test_containers_stopped']=True;save()
print(json.dumps(report,indent=2))
