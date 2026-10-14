"""Prepare or start exact-artifact replacements; never modify unrelated stacks."""
import datetime, json, os, pathlib, socket, subprocess, sys, time, urllib.request
os.umask(0o077)
p=pathlib.Path('/home/bekzat/release-46-20261009')
sha='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
project='refine-release46-20261009'
def run(a):return subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
def inspect(n):return json.loads(run(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
base=['docker','compose','-p',project,'-f',str(p/'compose.json')]
if sys.argv[1]=='prepare':
    indexes=[int(x) for x in sys.argv[2:]];assert indexes in [[0,1],[2]]
    source=json.loads((p/'manifest.json').read_text()); origin=json.loads((p/'audio-origin.json').read_text())
    assert set(origin)=={'AUDIO_CDN_HOST','AUDIO_ORIGIN_BASE_URL','AUDIO_ORIGIN_FALLBACK_TO_CDN'}
    services={};plan=[]
    for i in indexes:
        item=source[i];e=dict(item['environment']);e.update(origin)
        url=e['OLAF_CLICKHOUSE_URL'];assert '10.0.1.2' in url
        if 'socket_rcvbuf' not in url:e['OLAF_CLICKHOUSE_URL']=url+('&' if '?' in url else '?')+'socket_rcvbuf=0&socket_sndbuf=0'
        assert e['REDIS_ENABLED'].lower()=='true' and e['KAFKA_ENABLED'].lower()=='false'
        internal=[8085,8092,8091][i];external=[18085,18092,18091][i]
        assert item['command']==['SERVER_PORT='+str(internal)]
        with socket.socket() as s:s.bind(('127.0.0.1',external))
        service=['baseline','e','c'][i]
        cfg={'image':item['image'],'pull_policy':'never','restart':'unless-stopped',
             'entrypoint':item['entrypoint'],'command':item['command'],
             'environment':{k:v.replace('$','$$') for k,v in e.items()},
             'mem_limit':'4608m','cpus':3.0,'ports':['127.0.0.1:'+str(external)+':'+str(internal)],
             'logging':{'driver':'json-file','options':{'max-size':'20m','max-file':'3'}}}
        if item['working_dir']:cfg['working_dir']=item['working_dir']
        if item['user']:cfg['user']=item['user']
        services[service]=cfg
        plan.append({'index':i,'service':service,'name':project+'-'+service+'-1','port':external,'environment':e})
    (p/'compose.json').write_text(json.dumps({'services':services},indent=2))
    effective=json.loads(run(base+['config','--format','json']))
    for item in plan:assert effective['services'][item['service']]['environment']==item['environment']
    (p/'plan.json').write_text(json.dumps(plan))
    print(json.dumps({'prepared':len(plan),'memory_gib':4.5*len(plan),'cpu_cap':3*len(plan)}))
elif sys.argv[1:] == ['start']:
    assert not (p/'status.json').exists()
    source=json.loads((p/'source-status.json').read_text());assert source['phase']=='stopped'
    assert (datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(source['source_stopped_at'])).total_seconds()<600
    plan=json.loads((p/'plan.json').read_text())
    mem={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    assert mem['MemAvailable']/1024**2>4.5*len(plan)+4
    with urllib.request.urlopen('http://10.0.1.2:8123/ping',timeout=5) as r:assert r.read().strip()==b'Ok.'
    ids=run(['docker','ps','-q']).split();others={c['Name']:c['Id'] for c in json.loads(run(['docker','inspect',*ids]))}
    d={**source,'phase':'starting','new':[],'unrelated':others}
    (p/'status.json').write_text(json.dumps(d))
    try:
        run(base+['up','-d','--no-deps','--pull','never'])
        for item in plan:
            c=inspect(item['name']);assert dict(x.split('=',1) for x in c['Config']['Env'])==item['environment']
            assert c['HostConfig']['Memory']==4608*1024**2 and c['HostConfig']['NanoCpus']==3000000000
            assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]==sha
            for _ in range(60):
                try:
                    with urllib.request.urlopen('http://127.0.0.1:'+str(item['port'])+'/api/v1/health',timeout=2) as r:
                        if json.load(r).get('status')=='ok':break
                except Exception:pass
                time.sleep(2)
            else:raise RuntimeError('Replacement health timeout')
            d['new'].append({'name':item['name'],'id':c['Id'],'port':item['port']})
        assert all(inspect(name)['Id']==cid and inspect(cid)['State']['Running'] for name,cid in others.items())
        d.update(phase='running',healthy_at=utc());(p/'status.json').write_text(json.dumps(d,indent=2))
        for script in ['watch.py','watch-wave-tasks.py']:
            with (p/(script+'.log')).open('a') as log:
                proc=subprocess.Popen(['python3',str(p/script),str(p)],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
            d[script+'_pid']=proc.pid
        (p/'status.json').write_text(json.dumps(d,indent=2));print(json.dumps({'phase':'running','new':d['new']}))
    except BaseException as ex:
        d.update(phase='failed',error=type(ex).__name__);(p/'status.json').write_text(json.dumps(d));raise
elif sys.argv[1:] == ['rollback']:
    run(base+['down']);print(json.dumps({'replacement_stopped':True}))
else:raise SystemExit('Expected prepare INDEX..., start or rollback')
