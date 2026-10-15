import pathlib,json,subprocess,urllib.request,datetime,re,sys,time
p=pathlib.Path(sys.argv[1]);spec=json.loads((p/'spec.json').read_text());d=json.loads((p/'status.json').read_text());assert d['phase']=='running'
c=json.loads(subprocess.check_output(['docker','inspect',d['name']]))[0];assert c['Id']==d['new_id']
s=subprocess.check_output(['docker','logs','--since',d['started_at'],'--tail','30000',c['Id']],stderr=subprocess.STDOUT).decode(errors='replace');ts=[int(v) for v in re.findall(r'Redis monitor completed: .*? in (\d+)ms',s)]
errors={k:s.count(k) for k in ['Redis monitor failed','Redis worker error','Waiting for cancelled','Redis task ownership lost','java.lang.OutOfMemoryError','SocketTimeoutException','Window processing failed']}
try:
 with urllib.request.urlopen('http://127.0.0.1:'+str(spec['port'])+'/api/v1/health',timeout=3) as r:healthy=json.load(r)['status']=='ok'
except Exception:healthy=False
mem=dict((x.split(':')[0],int(x.split()[1])) for x in pathlib.Path('/proc/meminfo').read_text().splitlines());available=mem['MemAvailable']/1024**2
g={'host':spec['host'],'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'container_id':c['Id'],'completed':len(ts),'processing_ms':ts,'errors':errors,'health':healthy,'restarts':c['RestartCount'],'available_gib':round(available,2),'passed':len(ts)>=2 and healthy and c['State']['Running'] and not c['State']['OOMKilled'] and not c['RestartCount'] and not any(errors.values()) and available>=4}
(p/'replacement-gate.json').write_text(json.dumps(g,indent=2));print(json.dumps(g))
