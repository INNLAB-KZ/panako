"""Snapshot and retire the three refine consumers on the reassigned host."""
import copy, datetime, hashlib, json, os, pathlib, subprocess, sys, time
from redis_state import Redis, env, pairs

os.umask(0o077)
p = pathlib.Path('/home/bekzat/release-46-20261009')
p.mkdir(mode=0o700, exist_ok=True)
names = ['refine-move-20260927-refine-from-main-1',
         'refine-e-move-20261006-refine-from-main-1',
         'refine-c-move-20261006-refine-from-main-1']
sha = '60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
def run(a): return subprocess.check_output(a, text=True, stderr=subprocess.PIPE)
def inspect(n): return json.loads(run(['docker', 'inspect', n]))[0]
def utc(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(d):
    q=p/'status.tmp'; q.write_text(json.dumps(d,indent=2)); q.replace(p/'status.json')

if sys.argv[1:] == ['prepare']:
    assert not (p/'manifest.json').exists()
    cs=[inspect(n) for n in names]
    assert set(run(['docker','ps','--format','{{.Names}}']).split()) == set(names)
    manifest=[]
    for i,c in enumerate(cs):
        e=env(c); assert e['PANAKO_WORKER_MODE']=='refine' and e['CLICKHOUSE_HOST']=='10.0.1.2'
        assert e['MONITOR_PARALLEL_WINDOWS']=='2' and e['REDIS_WORKER_THREADS']=='1'
        assert c['State']['Running'] and not c['RestartCount'] and not c['Mounts']
        assert c['HostConfig']['Memory']==4608*1024**2
        assert run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0]==sha
        labels=c['Config']['Labels']; files=labels['com.docker.compose.project.config_files'].split(',')
        backups=[]
        for j,f in enumerate(files):
            q=pathlib.Path(f); raw=q.read_bytes(); backup=p/('before-'+str(i)+'-'+str(j)+q.suffix)
            backup.write_bytes(raw); backups.append({'path':f,'backup':str(backup),'sha256':hashlib.sha256(raw).hexdigest()})
        base=['docker','compose','-p',labels['com.docker.compose.project'],'--project-directory',labels['com.docker.compose.project.working_dir']]
        args=base+sum((['-f',f] for f in files),[])
        doc=json.loads(run(args+['config','--format','json']))
        service=labels['com.docker.compose.service']; assert list(doc['services'])==[service]
        proposed=copy.deepcopy(doc); proposed['services'][service]['restart']='no'
        proposed['services'][service].setdefault('deploy',{})['replicas']=0
        proposed['services'][service]['deploy'].setdefault('placement',{})
        proposed['services'][service]['deploy'].setdefault('resources',{})
        # Freeze the effective stack at the last override; escape interpolation.
        def escape(v):
            if isinstance(v,str): return v.replace('$','$$')
            if isinstance(v,list): return [escape(x) for x in v]
            if isinstance(v,dict): return {k:escape(x) for k,x in v.items()}
            return v
        proposal=p/('retired-'+str(i)+'.json'); proposal.write_text(json.dumps(escape(proposed),indent=2))
        effective=json.loads(run(base+sum((['-f',f] for f in files[:-1]),[])+['-f',str(proposal),'config','--format','json']))
        assert effective==proposed, 'Retirement proposal changes unexpected settings'
        manifest.append({'name':names[i],'id':c['Id'],'image':c['Image'],'environment':e,
                         'entrypoint':c['Config']['Entrypoint'],'command':c['Config']['Cmd'],
                         'user':c['Config']['User'],'working_dir':c['Config']['WorkingDir'],
                         'project':labels['com.docker.compose.project'],'service':service,
                         'files':files,'backups':backups,'proposal':str(proposal),
                         'restart':c['HostConfig']['RestartPolicy']['Name'],'inspect':c})
    assert len({x['image'] for x in manifest})==1
    (p/'manifest.json').write_text(json.dumps(manifest))
    print(json.dumps({'prepared':len(manifest),'image':manifest[0]['image']}))
elif sys.argv[1:] == ['stop']:
    manifest=json.loads((p/'manifest.json').read_text()); assert not (p/'status.json').exists()
    r=Redis(manifest[0]['environment']); stream='panako.monitor.refine.request'; group='panako-worker-refine'
    owners=[pairs(x)['name'] for x in r.cmd('XINFO','CONSUMERS',stream,group)]
    def pending(item):
        prefix=item['inspect']['Config']['Hostname']+'-'
        return [row for owner in owners if owner.startswith(prefix) for row in r.cmd('XPENDING',stream,group,'-','+',100,owner)]
    for item in manifest:
        assert inspect(item['id'])['State']['Running']
        for b in item['backups']: assert hashlib.sha256(pathlib.Path(b['path']).read_bytes()).hexdigest()==b['sha256']
    d={'phase':'draining','started_at':utc()}; save(d); frozen=set()
    try:
        deadline=time.monotonic()+120
        while time.monotonic()<deadline and len(frozen)<3:
            for item in manifest:
                if item['id'] in frozen: continue
                if not pending(item):
                    run(['docker','pause',item['id']]); time.sleep(0.5)
                    if not pending(item): frozen.add(item['id'])
                    else: run(['docker','unpause',item['id']])
            time.sleep(0.5)
        d['pending_before_stop']=[row for item in manifest for row in pending(item)]; save(d)
        for item in manifest:
            target=pathlib.Path(item['files'][-1]); tmp=target.with_suffix(target.suffix+'.retire.tmp')
            tmp.write_bytes(pathlib.Path(item['proposal']).read_bytes()); os.chmod(tmp,0o600); tmp.replace(target)
            run(['docker','update','--restart=no',item['id']])
            if item['id'] in frozen:
                run(['docker','kill',item['id']]); frozen.remove(item['id'])
            else: run(['docker','stop','--timeout','30',item['id']])
        d.update(phase='stopped',source_stopped_at=utc(),pending_after_stop=[row for item in manifest for row in pending(item)])
        assert not run(['docker','ps','-q']).strip()
        save(d); print(json.dumps(d))
    except BaseException:
        for cid in frozen: subprocess.run(['docker','unpause',cid],stdout=subprocess.DEVNULL)
        raise
elif sys.argv[1:] == ['rollback']:
    manifest=json.loads((p/'manifest.json').read_text())
    for item in manifest:
        for b in item['backups']:
            target=pathlib.Path(b['path']);tmp=target.with_suffix(target.suffix+'.restore.tmp')
            tmp.write_bytes(pathlib.Path(b['backup']).read_bytes());os.chmod(tmp,0o600);tmp.replace(target)
        run(['docker','update','--restart='+item['restart'],item['id']])
        run(['docker','start',item['id']])
    print(json.dumps({'restored':3}))
else: raise SystemExit('Expected prepare, stop or rollback')
