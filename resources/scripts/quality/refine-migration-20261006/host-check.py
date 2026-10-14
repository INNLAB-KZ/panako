import pathlib,time,json,subprocess,datetime

def sample():
 cpu=list(map(int,pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:9]));net={}
 for p in pathlib.Path('/sys/class/net').iterdir():
  if p.name=='lo' or p.name.startswith(('veth','docker','br-')):continue
  try:
   if (p/'operstate').read_text().strip()!='up':continue
   net[p.name]={k:int((p/'statistics'/k).read_text()) for k in ['rx_bytes','tx_bytes','rx_dropped','tx_dropped','rx_errors','tx_errors']}
  except OSError:pass
 return time.monotonic(),cpu,net
at,a,an=sample();time.sleep(10);bt,b,bn=sample();d=[y-x for x,y in zip(a,b)];total=sum(d);mem=dict((l.split(':')[0],int(l.split()[1])) for l in pathlib.Path('/proc/meminfo').read_text().splitlines());net={}
for k,v in bn.items():
 if k not in an:continue
 net[k]={'rx_mbps':round((v['rx_bytes']-an[k]['rx_bytes'])*8/(bt-at)/1e6,1),'tx_mbps':round((v['tx_bytes']-an[k]['tx_bytes'])*8/(bt-at)/1e6,1),'errors_drops':sum(v[x]-an[k][x] for x in ['rx_dropped','tx_dropped','rx_errors','tx_errors'])}
raw=subprocess.check_output(['docker','stats','--no-stream','--format','{{json .}}'],text=True);usage=[{k:c[k] for k in ['Name','CPUPerc','MemUsage']} for c in map(json.loads,raw.splitlines())]
print(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'cpu_busy_pct':round((total-d[3]-d[4])*100/total,1),'iowait_pct':round(d[4]*100/total,1),'available_gib':round(mem['MemAvailable']/1024**2,1),'network':net,'containers':usage}))
