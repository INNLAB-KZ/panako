import pathlib,json,subprocess,time,datetime
from gate import read_gate
p=pathlib.Path('/home/bekzat/jdbc-rollout-20261004');deadline=time.monotonic()+2400
while time.monotonic()<deadline:
 try:d=json.loads((p/'deployment.json').read_text())
 except (FileNotFoundError,json.JSONDecodeError):time.sleep(1);continue
 if d.get('error') or d['phase'].startswith('rolled_back'):raise SystemExit('Deployment failed')
 if d['phase']=='running':break
 time.sleep(2)
else:raise SystemExit('Deployment deadline')
with (p/'network.log').open('w') as log:net=subprocess.Popen(['python3',str(p/'network.py'),str(p),'1900'],stdout=log,stderr=log)
try:
 with (p/'watch.log').open('w') as log:subprocess.run(['python3',str(p/'watch.py')],stdout=log,stderr=log,check=True,timeout=2100)
 result=read_gate(p);result['at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
 if json.loads((p/'watch.json').read_text()).get('fatal'):
  result['expansion_held']=True
  result['operator_review_required']=True
 (p/'gate-result.json').write_text(json.dumps(result,indent=2))
finally:net.terminate();net.wait(timeout=10)
