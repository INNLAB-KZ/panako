import pathlib,json,subprocess,urllib.request,urllib.parse,datetime,re
p=pathlib.Path('/home/bekzat/refine-recovery-20261002');d=json.loads((p/'status.json').read_text());assert d['phase']=='running'
c=json.loads(subprocess.check_output(['docker','inspect',d['name']]))[0];assert c['Id']==d['new_id']
s=subprocess.check_output(['docker','logs','--since',d['started_at'],'--tail','10000',c['Id']],stderr=subprocess.STDOUT).decode(errors='replace')
times=[int(v) for v in re.findall(r'Redis monitor completed: .*? in (\d+)ms',s)]
errors={k:s.count(k) for k in ['Redis monitor failed','Redis worker error','Waiting for cancelled','Redis task ownership lost','java.lang.OutOfMemoryError','SocketTimeoutException','Window processing failed']}
port=int(next(v.split('=',1)[1] for v in c['Config']['Cmd'] if v.startswith('SERVER_PORT=')))
with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/api/v1/health',timeout=5) as r:healthy=json.load(r)['status']=='ok'
e=dict(x.split('=',1) for x in c['Config']['Env']);q=urllib.parse.parse_qs(urllib.parse.urlsplit(e['OLAF_CLICKHOUSE_URL'][5:]).query);assert q['socket_timeout']==['60000']
g={'host':'167.233.105.218','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'container_id':c['Id'],'completed':len(times),'processing_ms':times,'errors':errors,'health':healthy,'restarts':c['RestartCount'],'passed':bool(times) and healthy and c['State']['Running'] and not c['State']['OOMKilled'] and not c['RestartCount'] and not any(errors.values())}
(p/'replacement-gate.json').write_text(json.dumps(g,indent=2));print(json.dumps(g));assert g['passed']
