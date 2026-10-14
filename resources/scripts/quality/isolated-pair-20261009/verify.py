"""Read-only checks for the deployed isolated consumer pair."""
import base64, datetime, json, pathlib, subprocess, sys, urllib.request
from redis_state import Redis, env, pairs
p=pathlib.Path('/home/bekzat/isolated-pair-20261009')
s=json.loads((p/'status.json').read_text())
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'services':[]}
for item in s['services']:
    c=json.loads(subprocess.check_output(['docker','inspect',item['id']]))[0]
    e=env(c);r=Redis(e)
    assert c['State']['Running'] and not c['RestartCount'] and not c['State']['OOMKilled']
    assert e['REDIS_HOST']=='10.0.0.4' and e['CLICKHOUSE_HOST']=='10.0.1.2'
    groups=[pairs(x) for x in r.cmd('XINFO','GROUPS',item['stream'])]
    group=next(x for x in groups if x['name']==item['group'])
    consumers=[pairs(x) for x in r.cmd('XINFO','CONSUMERS',item['stream'],item['group'])]
    own=[x for x in consumers if x['name'].startswith(c['Config']['Hostname']+'-')]
    assert len(own)==1
    logs=subprocess.check_output(['docker','logs','--since',s['started_at'],'--tail','2000',item['id']],stderr=subprocess.STDOUT,text=True)
    # JVM startup prints ExitOnOutOfMemoryError as a flag; match actual exception class.
    errors={k:logs.count(k) for k in ['Redis monitor failed','Redis worker error','Redis read loop error',
                                   'Redis task heartbeat failed','java.lang.OutOfMemoryError','NOAUTH','WRONGPASS','NOPERM']}
    assert not any(errors.values())
    auth='Basic '+base64.b64encode((e['CLICKHOUSE_USER']+':'+e['CLICKHOUSE_PASSWORD']).encode()).decode()
    with urllib.request.urlopen(urllib.request.Request('http://10.0.1.2:8123/',data=b'SELECT 1',headers={'Authorization':auth}),timeout=5) as response:
        assert response.read().strip()==b'1'
    with urllib.request.urlopen('http://127.0.0.1:'+str(item['port'])+'/api/v1/health',timeout=3) as response:
        assert json.load(response)['status']=='ok'
    out['services'].append({'role':item['role'],'name':item['name'],'health':True,'db_select_ok':True,
                            'consumer':own,'group':group,'restarts':c['RestartCount'],'errors':errors})
for name,cid in s['unrelated'].items():
    c=json.loads(subprocess.check_output(['docker','inspect',name]))[0]
    assert c['Id']==cid and c['State']['Running']
    e=env(c)
    if e.get('REDIS_ENABLED','').lower()=='true':assert e['REDIS_HOST']=='10.0.0.2'
out['old_services_unchanged']=True
m={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
out['available_gib']=round(m['MemAvailable']/1024**2,2)
(p/'verification.json').write_text(json.dumps(out,indent=2))
print(json.dumps(out))
