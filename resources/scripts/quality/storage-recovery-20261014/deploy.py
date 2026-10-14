"""Bounded single-consumer storage recovery. Credentials remain in protected files."""
import datetime, hashlib, json, os, pathlib, subprocess, sys
os.umask(0o077)
p = pathlib.Path('/home/bekzat/storage-recovery-20261014')
sys.path.insert(0, str(p)); from redis_state import Redis, env, pairs

def run(args):
    x = subprocess.run(args, capture_output=True, text=True, timeout=180)
    if x.returncode:
        (p / 'command-error-private.json').write_text(json.dumps({'args':args[:3], 'stdout':x.stdout, 'stderr':x.stderr}))
        raise RuntimeError('Command failed; protected evidence retained')
    return x.stdout.strip()

def save(n, value): (p/n).write_text(json.dumps(value, indent=2))
def inspect(n): return json.loads(run(['docker','inspect',n]))[0]
names=['project-panako-store-'+x+'-1' for x in 'abcd']
compose=['docker','compose','-p','panako-storage-recovery-20261014','-f',str(p/'compose-private.json')]
if sys.argv[1]=='prepare':
    assert not (p/'plan.json').exists()
    old=[inspect(n) for n in names];save('before-private.json',old)
    e=env(old[0]); assert e['REDIS_HOST']=='10.0.0.2'
    r=Redis(e); assert r.cmd('PING')=='PONG'
    assert r.cmd('XPENDING','panako-store-requests','panako-workers')[0]==0
    save('queue-before.json',{'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'groups':[pairs(x) for x in r.cmd('XINFO','GROUPS','panako-store-requests')],
        'results':r.cmd('XINFO','STREAM','panako-store-results')[1]})
    manifest=json.loads((p/'release.json').read_text());assert hashlib.sha256((p/'panako.jar').read_bytes()).hexdigest()==manifest['jar_sha256']
    base_tag='ozen-panako:storage-base-20261014'
    run(['docker','tag',old[0]['Image'],base_tag])
    assert inspect(base_tag)['Id']==old[0]['Image']
    (p/'Dockerfile').write_text('FROM '+base_tag+'\nCOPY panako.jar /app/panako.jar\n')
    (p/'.dockerignore').write_text('*\n!Dockerfile\n!panako.jar\n')
    tag='ozen-panako:storage-recovery-20261014'
    run(['docker','build','--pull=false','--network=none','-t',tag,str(p)])
    image=inspect(tag)['Id']
    e.update(KAFKA_ENABLED='FALSE',KAFKA_MODE='STORE',REDIS_ENABLED='true',REDIS_WORKER_THREADS='1',
        PANAKO_WORKER_MODE='stage1',CLICKHOUSE_HOST='127.0.0.1',REDIS_BLOCK_MS='1000',
        JAVA_TOOL_OPTIONS='-Xms256m -Xmx2048m -XX:+ExitOnOutOfMemoryError',SERVER_PORT='8081',SERVER_MAX_UPLOAD_SIZE_MB='512',REDIS_STORE_PATCH_ENABLED='false')
    e.pop('JDK_JAVA_OPTIONS',None);e.pop('JAVA_OPTS',None)
    e['OLAF_CLICKHOUSE_URL']=e['OLAF_CLICKHOUSE_URL']
    assert '127.0.0.1:8123' in e['OLAF_CLICKHOUSE_URL'],'Expected Main direct write endpoint'
    service={'image':image,'entrypoint':old[0]['Config']['Entrypoint'],'command':['SERVER_PORT=8081'],'network_mode':'host',
        'environment':{k:v.replace('$','$$') for k,v in e.items()},'restart':'unless-stopped',
        'mem_limit':3*1024**3,'memswap_limit':3*1024**3,'cpus':2,'deploy':{'replicas':1},
        'logging':{'driver':'json-file','options':{'max-size':'20m','max-file':'3'}}}
    save('compose-private.json',{'services':{'store':service}})
    rendered=json.loads(run(compose+['config','--format','json']));assert rendered['services']['store']['environment']==e
    # Capacity includes both 60 GiB database limits and a 2 GiB host reserve.
    total=int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemTotal:')))*1024
    live=json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]))
    assert sum(c['HostConfig']['Memory'] for c in live if c['Name'].lstrip('/') not in names)+3*1024**3 < total-2*1024**3
    save('plan.json',{'jar':manifest['jar_sha256'],'image':image,'heap_gib':2,'limit_gib':3,'consumers':1,'redis':'10.0.0.2','clickhouse':'Main localhost:8123'})
    print((p/'plan.json').read_text())
elif sys.argv[1]=='apply':
    assert (p/'plan.json').exists() and not (p/'started.json').exists()
    old=json.loads((p/'before-private.json').read_text());r=Redis(env(old[0]))
    assert r.cmd('XPENDING','panako-store-requests','panako-workers')[0]==0
    for c in old:
        assert inspect(c['Id'])['State']['Running']
        run(['docker','update','--restart=no',c['Id']]);run(['docker','stop','--timeout','30',c['Id']])
    run(compose+['up','-d','--no-build','--pull','never'])
    c=inspect('panako-storage-recovery-20261014-store-1');plan=json.loads((p/'plan.json').read_text())
    assert c['Image']==plan['image'] and c['State']['Running']
    assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]==plan['jar']
    save('started.json',{'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'container':c['Id']})
    print(json.dumps({'started':True,'container':c['Name'],'limit_gib':3}))
else: raise ValueError('prepare/apply required')
