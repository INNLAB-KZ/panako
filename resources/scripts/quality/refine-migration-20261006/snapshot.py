import pathlib,json,subprocess,concurrent.futures
p=pathlib.Path(__file__).resolve().parent

def read(key):
 s=json.loads((p/'private'/key/'spec.json').read_text());raw=subprocess.check_output(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','bekzat@'+s['host'],'cat '+s['root']+'/watch.json'],timeout=25);d=json.loads(raw);(p/('watch-'+key+'.json')).write_bytes(raw)
 return {'key':key,'host':s['host'],**{k:d.get(k) for k in ['elapsed_seconds','completed','errors','restarts','health','cpu_busy_pct','available_gib','min_available_gib','network','finished_at','passed']},'alerts':len(d['alerts'])}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:out=list(pool.map(read,['a','b','c','d','e','h']))
(p/'observers.json').write_text(json.dumps(out,indent=2));print(json.dumps(out))
