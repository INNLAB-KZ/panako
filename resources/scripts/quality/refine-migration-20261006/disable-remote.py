import pathlib,json,subprocess,hashlib,copy,os,datetime,sys
os.umask(0o077);root=pathlib.Path(sys.argv[1]);spec=json.loads((root/'spec.json').read_text());target=pathlib.Path(spec['target_file'])
def inspect(n):return json.loads(subprocess.check_output(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
c=inspect(spec['source_name']);assert c['Id']==spec['source_id'] and c['State']['Running'];e=dict(v.split('=',1) for v in c['Config']['Env']);assert e['PANAKO_WORKER_MODE']=='refine'
assert hashlib.sha256(target.read_bytes()).hexdigest()==spec['original_sha256']
base=['docker','compose','--project-name',spec['source_project']]
for f in spec['base_files']:base+=['-f',f]
a=json.loads(subprocess.check_output(base+['-f',str(target),'config','--format','json']));b=json.loads(subprocess.check_output(base+['-f',str(root/'proposed.yml'),'config','--format','json']))
want=copy.deepcopy(a);svc=want['services'][spec['source_service']];svc['restart']='no';svc.setdefault('deploy',{})['replicas']=0;svc['deploy'].setdefault('resources',{});svc['deploy'].setdefault('placement',{});assert want==b,'Unexpected Compose changes'
gate=json.loads((root/'replacement-gate.json').read_text());assert gate['passed'] and gate['host']==spec['host'] and gate['completed']>=2 and gate['available_gib']>=4
assert (datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(gate['at'])).total_seconds()<300
allc=json.loads(subprocess.check_output(['docker','inspect',*subprocess.check_output(['docker','ps','-q'],text=True).split()]));other={v['Name']:v['Id'] for v in allc if v['Id']!=c['Id']}
(root/'before.inspect.json').write_text(json.dumps(c));(root/'before.yml').write_bytes(target.read_bytes());d={'phase':'stopping','started_at':utc(),'source_id':c['Id'],'source_name':spec['source_name'],'replacement':gate};(root/'status.json').write_text(json.dumps(d,indent=2))
tmp=target.with_name(target.name+'.tmp-20261006');tmp.write_bytes((root/'proposed.yml').read_bytes());os.chmod(tmp,target.stat().st_mode & 0o777);os.replace(tmp,target)
subprocess.run(['docker','update','--restart=no',c['Id']],check=True,stdout=subprocess.DEVNULL);subprocess.run(['docker','stop','--timeout','30',c['Id']],check=True,stdout=subprocess.DEVNULL)
n=inspect(c['Id']);assert not n['State']['Running'] and n['HostConfig']['RestartPolicy']['Name']=='no';assert all(inspect(k)['Id']==v and inspect(k)['State']['Running'] for k,v in other.items());assert json.loads(subprocess.check_output(base+['-f',str(target),'config','--format','json']))==want
d.update(phase='stopped',finished_at=utc(),other_containers_unchanged=True);(root/'status.json').write_text(json.dumps(d,indent=2));print(json.dumps(d))
