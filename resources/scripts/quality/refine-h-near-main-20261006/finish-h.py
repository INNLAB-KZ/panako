import json,subprocess,pathlib,time,os,datetime
os.umask(0o077);p=pathlib.Path(__file__).resolve().parent;deadline=time.monotonic()+1800
while time.monotonic()<deadline:
 subprocess.run(['python3',str(p/'performance-gate.py')],check=True,timeout=100);d=json.loads((p/'performance-gate.json').read_text())
 if d['watch_alerts'] or any(d['startup']['errors'].values()) or not d['startup']['health'] or any(x['processing_time_ms']>600000 for x in d['hourly_full_segment_results']):raise RuntimeError('Hold: pilot error or hourly latency target exceeded')
 if d['passed']:
  for phase in ['before','after']:
   if phase=='after':subprocess.run(['python3',str(p/'disable.py'),'h'],check=True,timeout=180)
   raw=subprocess.check_output(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','bekzat@116.202.112.101','python3 /home/bekzat/refine-h-disable-20261006/pending-source.py'],timeout=30);(p/('source-pending-'+phase+'.json')).write_bytes(raw)
  (p/'finish-status.json').write_text(json.dumps({'phase':'source_stopped','at':datetime.datetime.now(datetime.timezone.utc).isoformat()}));break
 time.sleep(30)
else:raise RuntimeError('Hold: insufficient hourly samples')
