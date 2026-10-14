import json,subprocess,pathlib,datetime
p=pathlib.Path(__file__).resolve().parent;spec=json.loads((p/'private/h/spec.json').read_text());host='bekzat@'+spec['host'];root=spec['root'];opts=['-o','BatchMode=yes','-o','ConnectTimeout=10']
def remote(cmd):return subprocess.check_output(['ssh',*opts,host,cmd],timeout=35)
gate=json.loads(remote('python3 '+root+'/check.py '+root));tasks=json.loads(remote('cat '+root+'/tasks.json'));watch=json.loads(remote('cat '+root+'/watch.json'))
rows=[x for x in tasks['responses'] if x.get('matched_own_completion') and x.get('status')=='ok' and 3300 <= (x.get('duration_seconds') or 0) <=3900 and 3300 <= x.get('segment_seconds',0)<=3900]
passed=gate['passed'] and len(rows)>=2 and all(x['processing_time_ms']<=600000 for x in rows) and not watch['alerts'] and watch['min_available_gib']>=4
result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':passed,'startup':gate,'hourly_full_segment_results':rows,'watch_alerts':len(watch['alerts']),'min_available_gib':watch['min_available_gib']};(p/'performance-gate.json').write_text(json.dumps(result,indent=2));(p/'tasks.json').write_text(json.dumps(tasks,indent=2));(p/'watch.json').write_text(json.dumps(watch,indent=2));print(json.dumps(result))
