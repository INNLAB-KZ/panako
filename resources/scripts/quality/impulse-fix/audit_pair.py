import pathlib,subprocess,json,os,time
os.umask(0o077);p=pathlib.Path('/home/bekzat/impulse-fix-20260929');s={'phase':'starting'};active=None

def save():
 t=p/'fingerprint-comparison.tmp';t.write_text(json.dumps(s,indent=2));t.replace(p/'fingerprint-comparison.json')
try:
 for mode in ['baseline','candidate']:
  active='impulse-audit-20260929-'+mode
  jar='/app/panako.jar' if mode=='baseline' else '/probe/panako.jar'
  subprocess.check_output(['docker','run','-d','--name',active,'--network','none','--restart=no','--cpus','2','--memory','4831838208','--memory-swap','4831838208','--env-file','/home/bekzat/compact-query-v2-20260929/probe.env','-e','JDK_JAVA_OPTIONS=-Xmx3584m -XX:+ExitOnOutOfMemoryError','-v',str(p)+':/probe','--entrypoint','java','local/ozen-panako:input-pilot-20260925-5d50209b','-cp','/probe/audit.jar:'+jar,'be.panako.http.WindowAudit','/probe/recording.m4a','/probe/audit-'+mode])
  begin=time.monotonic();s['phase']='running_'+mode;save()
  while True:
   c=json.loads(subprocess.check_output(['docker','inspect',active]))[0]
   if not c['State']['Running']:
    if c['State']['ExitCode']!=0:raise RuntimeError(mode+' audit failed')
    break
   if time.monotonic()-begin>600:raise RuntimeError('Audit timeout')
   time.sleep(5)
  active=None
 a=[json.loads(x) for x in (p/'audit-baseline/windows.jsonl').read_text().splitlines()]
 b=[json.loads(x) for x in (p/'audit-candidate/windows.jsonl').read_text().splitlines()]
 assert len(a)==len(b)==119
 mismatches=[];changed=[]
 for old,new in zip(a,b):
  assert old['offset']==new['offset']
  if old['sha256']!=new['sha256']:changed.append(old['offset'])
  if old['offset']==1740:
   if old['clean_sha256']!=new['sha256'] or old['clean_count']!=new['fingerprints']:mismatches.append(1740)
   s['heavy_window']={'before':old['fingerprints'],'after':new['fingerprints'],'expected_without_four_noise_frames':old['clean_count']}
  elif old['sha256']!=new['sha256']:mismatches.append(old['offset'])
 s.update(phase='passed' if not mismatches else 'failed',windows=len(a),changed_offsets=changed,unexpected_mismatches=mismatches)
except Exception as e:s.update(phase='failed',error=type(e).__name__+': '+str(e)[:300])
finally:
 if active:subprocess.run(['docker','stop','--time','3',active],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 save()
