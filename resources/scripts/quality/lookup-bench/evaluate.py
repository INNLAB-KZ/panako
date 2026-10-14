import pathlib,json,time,datetime,urllib.parse,urllib.request,os
os.umask(0o077);p=pathlib.Path('/home/bekzat/lookup-bench-20260929')
deadline=time.monotonic()+5500
while time.monotonic()<deadline:
 s=json.loads((p/'status.json').read_text())
 if s['phase'] in ['completed','failed']:break
 time.sleep(20)
else:raise SystemExit('Benchmark evaluation deadline')
if s['phase']=='failed':raise SystemExit('Benchmark failed; inspect status.json')
time.sleep(15)
e=dict(x.split('=',1) for x in (p/'bench.env').read_text().splitlines());a=urllib.parse.parse_qs(urllib.parse.urlsplit(e['OLAF_CLICKHOUSE_URL'][5:]).query)
q="SELECT if(startsWith(query,'/* lookup_bench_20260929_baseline */'),'baseline','sorted') AS mode,count() AS queries,sum(read_rows) AS read_rows,sum(read_bytes) AS read_bytes,sum(result_rows) AS result_rows,sum(result_bytes) AS result_bytes,sum(query_duration_ms) AS server_ms,quantile(0.95)(query_duration_ms) AS p95_ms,max(query_duration_ms) AS max_ms FROM system.query_log WHERE event_time>=toDateTime('2026-09-29 04:01:00','UTC') AND type='QueryFinish' AND (startsWith(query,'/* lookup_bench_20260929_baseline */') OR startsWith(query,'/* lookup_bench_20260929_sorted */')) GROUP BY mode FORMAT JSONEachRow"
args=urllib.parse.urlencode({'user':a['user'][0],'password':a['password'][0],'query':q})
with urllib.request.urlopen('http://10.0.1.2:8123/?'+args,timeout=30) as r:metrics=[json.loads(x) for x in r.read().decode().splitlines()]
baseline,sorted_run=s['runs'];ratio=sorted_run['processing_time_ms']/baseline['processing_time_ms']
result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'baseline':baseline,'sorted':sorted_run,'time_reduction_pct':round((1-ratio)*100,2),'speedup':round(1/ratio,3),'raw_matches_equal':s['raw_matches_equal'],'response_equal':s['response_equal'],'lookup_result_row_counts_equal':baseline['lookup']['rows']==sorted_run['lookup']['rows'],'server_metrics':metrics,'production_changed':False,'limitations':['sequential A/B on live DB','two-CPU test cap','one recording; zero-track output insufficient for broad quality equivalence']}
(p/'evaluation.json').write_text(json.dumps(result,indent=2))
