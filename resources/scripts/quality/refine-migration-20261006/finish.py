import subprocess,json,pathlib,time,os,datetime
os.umask(0o077);p=pathlib.Path(__file__).resolve().parent;keys=['c','d','e','h'];deadline=time.monotonic()+1800
while keys and time.monotonic()<deadline:
 for key in keys[:]:
  spec=json.loads((p/'private'/key/'spec.json').read_text())
  try:
   raw=subprocess.check_output(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','bekzat@'+spec['host'],'python3 '+spec['root']+'/check.py '+spec['root']],timeout=35)
   gate=json.loads(raw);(p/('gate-'+key+'.json')).write_bytes(raw)
   print(json.dumps({'key':key,**gate}),flush=True)
   if any(gate['errors'].values()) or gate['restarts'] or not gate['health'] or gate['available_gib']<4:raise RuntimeError('Pilot health gate failed: '+key)
   if gate['passed']:
    subprocess.run(['python3',str(p/'disable.py'),key],check=True,timeout=180);keys.remove(key)
  except Exception as exc:
   (p/'finish-status.json').write_text(json.dumps({'phase':'operator_review_required','key':key,'error':type(exc).__name__}));raise
 if keys:time.sleep(30)
result={'phase':'complete' if not keys else 'pending','remaining':keys,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()};(p/'finish-status.json').write_text(json.dumps(result));print(json.dumps(result),flush=True)
