import pathlib,json,subprocess,time,datetime,urllib.request,re,os
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-f2-move-20261004');d=json.loads((p/'status.json').read_text());assert d['phase']=='running'
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
start=time.monotonic();out={'started_at':utc(),'alerts':[]}
while time.monotonic()-start<1800:
 c=json.loads(subprocess.check_output(['docker','inspect',d['name']]))[0];assert c['Id']==d['new_id']
 s=subprocess.check_output(['docker','logs','--since',d['healthy_at'],'--tail','50000',c['Id']],stderr=subprocess.STDOUT).decode(errors='replace')
 errors={k:s.count(k) for k in ['Redis monitor failed','Redis worker error','Waiting for cancelled','Redis task ownership lost','java.lang.OutOfMemoryError','SocketTimeoutException','Window processing failed']}
 times=[int(v) for v in re.findall(r'Redis monitor completed: .*? in (\d+)ms',s)]
 try:
  with urllib.request.urlopen('http://127.0.0.1:8088/api/v1/health',timeout=3) as r:health=json.load(r)['status']=='ok'
 except Exception:health=False
 if not health or c['RestartCount'] or not c['State']['Running'] or c['State']['OOMKilled'] or any(errors.values()):
  out['alerts'].append({'at':utc(),'errors':errors,'health':health,'restarts':c['RestartCount']})
 out.update(last_at=utc(),elapsed_seconds=round(time.monotonic()-start),completed=len(times),processing_ms=times,errors=errors,health=health,restarts=c['RestartCount'])
 t=p/'watch.tmp';t.write_text(json.dumps(out,indent=2));t.replace(p/'watch.json')
 if not c['State']['Running'] or c['RestartCount'] or c['State']['OOMKilled']:break
 time.sleep(15)
out.update(finished_at=utc(),passed=not out['alerts'] and out['completed']>=2 and time.monotonic()-start>=1800);(p/'watch.json').write_text(json.dumps(out,indent=2))
