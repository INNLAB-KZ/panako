import pathlib,json,time,datetime,sys,os,subprocess,concurrent.futures
os.umask(0o077);root=pathlib.Path(sys.argv[1]);duration=int(sys.argv[2]);start=time.monotonic()
def snapshot():
 interfaces={}
 for p in pathlib.Path('/sys/class/net').iterdir():
  if p.name=='lo' or p.name.startswith(('docker','veth','br-')):continue
  try:
   if (p/'operstate').read_text().strip()!='up':continue
   n={k:int((p/'statistics'/k).read_text()) for k in ['rx_bytes','tx_bytes','rx_dropped','tx_dropped','rx_errors','tx_errors']}
   try:n['speed_mbps']=int((p/'speed').read_text())
   except OSError:n['speed_mbps']=None
   interfaces[p.name]=n
  except OSError:continue
 lines=pathlib.Path('/proc/net/snmp').read_text().splitlines();tcp={}
 for i,line in enumerate(lines):
  if line.startswith('Tcp:'):
   tcp={k:int(v) for k,v in zip(line.split()[1:],lines[i+1].split()[1:])};break
 result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'interfaces':interfaces,'host_tcp':tcp,'tcp_scope':'managed_containers'}
 expected={x['name']:x['id'] for x in json.loads((root/'deployment.json').read_text())['observation_containers']}
 def collect(item):
  name,identifier=item
  lines=subprocess.check_output(['docker','exec',identifier,'cat','/proc/net/snmp'],text=True,timeout=5).splitlines()
  for i,line in enumerate(lines):
   if line.startswith('Tcp:'):
    values=dict(zip(line.split()[1:],map(int,lines[i+1].split()[1:])))
    return name,{'id':identifier,**values}
  raise RuntimeError('Missing TCP counters')
 try:
  with concurrent.futures.ThreadPoolExecutor(max_workers=len(expected)) as pool:containers=dict(pool.map(collect,expected.items()))
  result['containers']=containers
  result['tcp']={key:sum(c[key] for c in containers.values()) for key in ['OutSegs','RetransSegs']}
 except Exception as exc:
  result.update(collection_error=type(exc).__name__,containers={},tcp={})
 return result
while time.monotonic()-start<duration:
 with (root/'network.jsonl').open('a') as f:f.write(json.dumps(snapshot())+'\n')
 time.sleep(10)
