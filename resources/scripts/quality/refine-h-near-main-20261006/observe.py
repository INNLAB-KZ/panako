import pathlib,json,subprocess,time,datetime,urllib.request,re,os,sys
os.umask(0o077);p=pathlib.Path(sys.argv[1]);spec=json.loads((p/'spec.json').read_text());d=json.loads((p/'status.json').read_text());assert d['phase']=='running'
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def host_sample():
 cpu=list(map(int,pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:]));net={}
 for line in pathlib.Path('/proc/net/dev').read_text().splitlines()[2:]:
  n,s=line.split(':');n=n.strip()
  if n!='lo' and not n.startswith(('veth','docker','br-')):net[n]=list(map(int,s.split()))
 mem=dict((x.split(':')[0],int(x.split()[1])) for x in pathlib.Path('/proc/meminfo').read_text().splitlines());return cpu,net,mem['MemAvailable']/1024**2
start=time.monotonic();last_t=start;last_cpu,last_net,mem=host_sample();out={'started_at':utc(),'alerts':[],'min_available_gib':mem}
while time.monotonic()-start<1800:
 c=json.loads(subprocess.check_output(['docker','inspect',d['name']]))[0];assert c['Id']==d['new_id']
 s=subprocess.check_output(['docker','logs','--since',d['healthy_at'],'--tail','50000',c['Id']],stderr=subprocess.STDOUT).decode(errors='replace');errors={k:s.count(k) for k in ['Redis monitor failed','Redis worker error','Waiting for cancelled','Redis task ownership lost','java.lang.OutOfMemoryError','SocketTimeoutException','Window processing failed']};times=[int(v) for v in re.findall(r'Redis monitor completed: .*? in (\d+)ms',s)]
 try:
  with urllib.request.urlopen('http://127.0.0.1:'+str(spec['port'])+'/api/v1/health',timeout=3) as r:health=json.load(r)['status']=='ok'
 except Exception:health=False
 cpu,net,mem=host_sample();now=time.monotonic();dt=max(now-last_t,0.01);delta=[b-a for a,b in zip(last_cpu,cpu)];total=max(sum(delta[:8]),1);rates={}
 for k,v in net.items():
  if k not in last_net:continue
  a=last_net[k];rates[k]={'rx_mbps':round((v[0]-a[0])*8/dt/1e6,2),'tx_mbps':round((v[8]-a[8])*8/dt/1e6,2),'errors_drops':sum(v[i]-a[i] for i in [2,3,10,11])}
 out['min_available_gib']=min(out['min_available_gib'],mem)
 if not health or c['RestartCount'] or not c['State']['Running'] or c['State']['OOMKilled'] or any(errors.values()) or mem<3:
  out['alerts'].append({'at':utc(),'errors':errors,'health':health,'restarts':c['RestartCount'],'available_gib':round(mem,2)})
 out.update(last_at=utc(),elapsed_seconds=round(now-start),completed=len(times),processing_ms=times,errors=errors,health=health,restarts=c['RestartCount'],cpu_busy_pct=round((total-delta[3]-delta[4])*100/total,1),available_gib=round(mem,2),network=rates)
 t=p/'watch.tmp';t.write_text(json.dumps(out,indent=2));t.replace(p/'watch.json')
 with (p/'samples.jsonl').open('a') as f:f.write(json.dumps({k:out[k] for k in ['last_at','completed','cpu_busy_pct','available_gib','network']})+'\n')
 if not c['State']['Running'] or c['RestartCount'] or c['State']['OOMKilled']:break
 last_t=now;last_cpu=cpu;last_net=net;time.sleep(15)
out.update(finished_at=utc(),passed=not out['alerts'] and out['completed']>=2 and time.monotonic()-start>=1800);(p/'watch.json').write_text(json.dumps(out,indent=2))
