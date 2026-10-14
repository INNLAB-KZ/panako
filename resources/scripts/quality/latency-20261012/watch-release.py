"""Read-only acceptance after each new service has produced a real queue response."""
import datetime,json,os,pathlib,subprocess,sys,time,urllib.request
sys.path.insert(0,'/home/bekzat/isolated-pair-20261009');from redis_state import Redis,env,pairs
os.umask(0o077);p=pathlib.Path('/home/bekzat/latency-20261012');start=time.monotonic();quiet=None
def save(x):
 t=p/'acceptance.tmp';t.write_text(json.dumps(x,indent=2));t.replace(p/'acceptance.json')
 with (p/'acceptance-history.jsonl').open('a') as f:f.write(json.dumps(x)+'\n')
while time.monotonic()-start<5400:
 deploy=json.loads((p/'deployment-status.json').read_text())
 if deploy['phase']=='failed':raise SystemExit('Deployment failed; inspect rollback')
 if deploy['phase']!='healthy':time.sleep(10);continue
 roles={};alerts=[]
 for item in deploy['roles']:
  role=item['role'];c=json.loads(subprocess.check_output(['docker','inspect',item['new_id']]))[0];e=env(c);r=Redis(e)
  assert e['REDIS_HOST']=='10.0.0.4' and e['CLICKHOUSE_HOST']=='10.0.1.2'
  stream='panako.monitor.'+('refine.' if role=='refine' else '')
  after=str(int(datetime.datetime.fromisoformat(item['at']).timestamp()*1000))+'-0'
  responses=[]
  for mid,fields in r.cmd('XRANGE',stream+'response',after,'+','COUNT',500):
   body=json.loads(pairs(fields)['value']);responses.append({k:body.get(k) for k in ['recording_id','status','duration_seconds','processing_time_ms']})
  groups={s:[pairs(x) for x in r.cmd('XINFO','GROUPS',stream+s)] for s in ['request','response']}
  port=18080 if role=='stage1' else 18081
  try:
   with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/api/v1/health',timeout=3) as x:healthy=json.load(x)['status']=='ok'
  except Exception:healthy=False
  logs=subprocess.check_output(['docker','logs','--since',item['at'],c['Id']],stderr=subprocess.STDOUT,text=True)
  errors={k:logs.count(k) for k in ['Redis monitor failed','Redis worker error','Redis read loop error','Redis task heartbeat failed','Redis task not acknowledged','java.lang.OutOfMemoryError','Window processing failed','SocketTimeoutException']}
  if not healthy or not c['State']['Running'] or c['RestartCount'] or c['State']['OOMKilled'] or any(errors.values()) or any(x['status']!='ok' for x in responses):alerts.append(role)
  roles[role]={'healthy':healthy,'restarts':c['RestartCount'],'oom':c['State']['OOMKilled'],'errors':errors,'responses':responses,'groups':groups}
 mem={s.split(':')[0]:int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()}
 if mem['MemAvailable']<3*1024**2:alerts.append('low_available_memory')
 ready=not alerts and len(roles)==2 and all(v['responses'] and all(g['lag']==0 and g['pending']==0 for group in v['groups'].values() for g in group) for v in roles.values())
 if ready:
  if quiet is None:quiet=time.monotonic()
 else:quiet=None
 passed=quiet is not None and time.monotonic()-quiet>=60
 out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'roles':roles,'alerts':alerts,'available_gib':round(mem['MemAvailable']/1024**2,2),'passed':passed}
 save(out)
 if passed:break
 time.sleep(20)
else:raise SystemExit('Acceptance deadline exceeded; inspect current workload')
print(json.dumps(out))
