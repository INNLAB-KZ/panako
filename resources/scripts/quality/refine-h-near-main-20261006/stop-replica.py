import pathlib,subprocess,json,os,hashlib,copy,datetime
os.umask(0o077);p=pathlib.Path('/home/bekzat/ch-retire-20261006');s=json.loads((p/'ch-spec.json').read_text());audit=json.loads((p/'db-audit.json').read_text());assert (datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(audit['at'])).total_seconds()<600
for host in ['10.0.0.21:8123','10.0.0.12:8123','10.0.0.10:8123','127.0.0.1:8123']:
 a=audit['hosts'][host];assert not a['distributed'] and not a['cluster_target'];assert len(a['replicas'])==2 and all(not x['is_readonly'] and not x['is_session_expired'] and x['queue_size']==0 and x['active_replicas']>=7 for x in a['replicas'])
assert not audit['hosts']['10.0.0.21:8123']['processes'];assert s['files']==['/srv/clickhouse/docker-compose.yml'];target=pathlib.Path(s['files'][0]);assert hashlib.sha256(target.read_bytes()).hexdigest()==s['sha256']
def run(a):return subprocess.check_output(a,text=True)
c=json.loads(run(['docker','inspect','clickhouse']))[0];assert c['Id']==s['id'] and c['State']['Running'] and c['Config']['Image'].startswith('clickhouse/clickhouse-server:26.3')
base=['docker','compose','--project-name',s['project'],'--project-directory',str(target.parent)];a=json.loads(run(base+['-f',str(target),'config','--format','json']));b=json.loads(run(base+['-f',str(p/'ch-proposed.yml'),'config','--format','json']));want=copy.deepcopy(a);v=want['services'][s['service']];v['restart']='no';v.setdefault('deploy',{})['replicas']=0
for o in [want,b]:
 dep=o['services'][s['service']].get('deploy',{})
 for k in ['resources','placement']:
  if dep.get(k)=={}:dep.pop(k)
assert want==b,'Unexpected Compose changes'
other={v['Name']:v['Id'] for v in json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()])) if v['Id']!=c['Id']};d={'phase':'stopping','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'id':c['Id'],'data_mounts':[m['Source'] for m in c['Mounts']]};(p/'status.json').write_text(json.dumps(d))
assert hashlib.sha256(target.read_bytes()).hexdigest()==s['sha256']
run(['docker','run','--rm','--network','none','--read-only','--entrypoint','/bin/sh','--mount','type=bind,src=/srv/clickhouse,dst=/target','--mount','type=bind,src='+str(p)+',dst=/input,readonly',c['Config']['Image'],'-c','set -eu; cp -p /target/docker-compose.yml /target/docker-compose.retire.tmp; cat /input/ch-proposed.yml > /target/docker-compose.retire.tmp; mv /target/docker-compose.retire.tmp /target/docker-compose.yml'])
assert target.read_bytes()==(p/'ch-proposed.yml').read_bytes()
run(['docker','update','--restart=no',c['Id']]);run(['docker','stop','--timeout','60',c['Id']]);n=json.loads(run(['docker','inspect',c['Id']]))[0];assert not n['State']['Running'] and n['HostConfig']['RestartPolicy']['Name']=='no'
for name,identifier in other.items():
 v=json.loads(run(['docker','inspect',name]))[0];assert v['Id']==identifier and v['State']['Running']
assert all(pathlib.Path(m['Source']).exists() for m in c['Mounts']);d.update(phase='stopped',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),other_containers_unchanged=True,data_preserved=True);(p/'status.json').write_text(json.dumps(d,indent=2));print(json.dumps(d))
