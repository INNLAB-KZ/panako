"""Deploy one Stage-1 and one refine against an explicitly supplied Redis.

Redis password is supplied through stdin, held in memory, and passed to Compose
via the child environment. It is not written to a deployment secret file.
"""
import datetime, json, os, pathlib, socket, subprocess, sys, time, urllib.request
from redis_state import Redis, pairs

os.umask(0o077)
p=pathlib.Path('/home/bekzat/isolated-pair-20261009')
password=sys.stdin.readline().strip();assert password
process_env=dict(os.environ,PAIR_REDIS_PASSWORD=password)
sha='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
base=['docker','compose','-p','panako-isolated-pair','-f',str(p/'compose.json')]
def run(a):return subprocess.check_output(a,env=process_env,text=True,stderr=subprocess.PIPE)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
assert not (p/'status.json').exists(),'Deployment already attempted; inspect status before retry'
doc=json.loads(run(base+['config','--format','json']))
assert set(doc['services'])=={'stage1','refine'}
for role,s in doc['services'].items():
    e=s['environment'];assert e['REDIS_HOST']=='10.0.0.4' and e['REDIS_PASSWORD']==password
    assert e['REDIS_PORT']=='6379' and e['PANAKO_WORKER_MODE']==role and e['KAFKA_MODE']=='MONITOR'
    assert e['CLICKHOUSE_HOST']=='10.0.1.2' and e['KAFKA_ENABLED'].lower()=='false'
    assert e['MONITOR_PARALLEL_WINDOWS']=='2' and e['REDIS_WORKER_THREADS']=='1'
    assert '3584m' in (e.get('JAVA_TOOL_OPTIONS','')+e.get('JDK_JAVA_OPTIONS',''))
    assert int(s['mem_limit'])==4608*1024**2 and float(s['cpus'])==3.0
    run(['docker','image','inspect',s['image']])
for port in [18080,18081]:
    with socket.socket() as s:s.bind(('127.0.0.1',port))
mem={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
assert mem['MemAvailable']/1024**2>13
with urllib.request.urlopen('http://10.0.1.2:8123/ping',timeout=5) as r:assert r.read().strip()==b'Ok.'
r=Redis({'REDIS_HOST':'10.0.0.4','REDIS_PORT':'6379','REDIS_PASSWORD':password})
assert r.cmd('PING')=='PONG'
server=dict(line.split(':',1) for line in r.cmd('INFO','server').splitlines() if ':' in line and not line.startswith('#'))
version=server['redis_version'];assert tuple(map(int,version.split('.')[:2]))>=(6,2)
streams=['panako.monitor.request','panako.monitor.refine.request']
before={stream:([pairs(x) for x in r.cmd('XINFO','GROUPS',stream)] if r.cmd('EXISTS',stream) else []) for stream in streams}
ids=run(['docker','ps','-q']).split();others={c['Name']:c['Id'] for c in json.loads(run(['docker','inspect',*ids]))}
d={'phase':'starting','started_at':utc(),'redis_host':'10.0.0.4','redis_port':6379,'redis_version':version,'db_host':'10.0.1.2','groups_before':before,'unrelated':others,'services':[]}
def save():
    q=p/'status.tmp';q.write_text(json.dumps(d,indent=2));q.replace(p/'status.json')
save()
try:
    run(base+['up','-d','--no-deps','--pull','never'])
    for role,port,stream,group in [('stage1',18080,streams[0],'panako-workers'),('refine',18081,streams[1],'panako-worker-refine')]:
        name='panako-isolated-pair-'+role+'-1';c=inspect(name)
        e=dict(x.split('=',1) for x in c['Config']['Env']);assert e==doc['services'][role]['environment']
        assert c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==3000000000
        assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]==sha
        for _ in range(60):
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/api/v1/health',timeout=2) as res:healthy=json.load(res).get('status')=='ok'
            except Exception:healthy=False
            if healthy:break
            time.sleep(1)
        assert healthy,'HTTP health failed for '+role
        groups=[pairs(x) for x in r.cmd('XINFO','GROUPS',stream)];assert any(x['name']==group for x in groups)
        d['services'].append({'role':role,'name':name,'id':c['Id'],'port':port,'stream':stream,'group':group,'health':True})
    assert all(inspect(name)['Id']==cid and inspect(cid)['State']['Running'] for name,cid in others.items())
    d.update(phase='running',healthy_at=utc(),unrelated_unchanged=True);save()
    print(json.dumps({k:v for k,v in d.items() if k!='unrelated'}))
except BaseException as ex:
    # Preserve existing production stacks; stop only the new isolated project.
    cleanup=subprocess.run(base+['down'],env=process_env,capture_output=True)
    d.update(phase='failed',error=type(ex).__name__,cleanup_exit=cleanup.returncode);save();raise
