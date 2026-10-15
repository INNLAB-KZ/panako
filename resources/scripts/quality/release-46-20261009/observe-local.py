"""Collect bounded read-only acceptance snapshots from both replacement hosts."""
import concurrent.futures, json, pathlib, statistics, subprocess, time

root=pathlib.Path('build/release-46-20261009')
hosts=['167.233.61.228','157.180.26.0']
def collect(host):
    result={}
    for filename in ['watch.json','tasks.json']:
        r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','bekzat@'+host,
                          'cat /home/bekzat/release-46-20261009/'+filename],capture_output=True,text=True,timeout=25)
        if r.returncode:raise RuntimeError('Cannot read '+host+'/'+filename)
        result[filename]=json.loads(r.stdout)
        (root/(host+'-'+filename)).write_text(json.dumps(result[filename],indent=2))
    w=result['watch.json'];t=result['tasks.json']
    full=[x['processing_time_ms'] for x in t['responses'] if x.get('matched_own_completion') and x['status']=='ok'
          and 3300<=float(x.get('duration_seconds') or 0)<=3900 and x['segment_seconds']>=3300]
    return {'host':host,'at':w['at'],'elapsed':w['elapsed_seconds'],'finished':w.get('finished',False),
            'passed':w.get('passed'), 'alerts':len(w['alerts']),
            'completed':[len(x['processing_ms']) for x in w['containers']],
            'all_recovered':w['all_recovered'],
            'recovered':sum(not x['pending'] and any(v['status']=='ok' for v in x['responses']) for x in w['tracked'].values()),
            'ram_available_gib':w['available_gib'],'cpu_pct':round(w['resources']['cpu_busy_pct'],1),
            'private_rx_mbps':round(w['resources']['network']['enp7s0']['rx_mbps'],1),
            'full_hour_count':len(full),'full_hour_mean_ms':round(statistics.mean(full)) if full else None,
            'full_hour_max_ms':max(full,default=0)}

deadline=time.monotonic()+2400
while time.monotonic()<deadline:
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:rows=list(pool.map(collect,hosts))
    (root/'acceptance-summary.json').write_text(json.dumps(rows,indent=2))
    print(json.dumps(rows),flush=True)
    if all(x['finished'] for x in rows):
        raise SystemExit(0 if all(x['passed'] for x in rows) else 1)
    time.sleep(45)
raise SystemExit('Acceptance deadline exceeded')
