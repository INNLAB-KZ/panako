import subprocess,pathlib,json,sys,os,copy,hashlib,yaml
os.umask(0o077);key=sys.argv[1];assert key in ['a','b','c','d','e','h'];p=pathlib.Path(__file__).resolve().parent;local=p/'private'/key;spec=json.loads((local/'spec.json').read_text());opts=['-o','BatchMode=yes','-o','ConnectTimeout=8'];main='bekzat@116.202.112.101';dest='bekzat@'+spec['host']
def ssh(host,cmd):return subprocess.check_output(['ssh',*opts,host,cmd])
gate=json.loads(ssh(dest,'python3 '+spec['root']+'/check.py '+spec['root']));assert gate['passed'],gate
c=json.loads(ssh(main,'docker inspect '+spec['source_name']))[0];assert c['Id']==spec['source_id']
l=c['Config']['Labels'];files=l['com.docker.compose.project.config_files'].split(',');target=files[-1];assert target.startswith('/home/bekzat/project/')
raw=ssh(main,'cat '+target);d=yaml.safe_load(raw);svc=copy.deepcopy(d['services'][spec['source_service']]);svc['restart']='no';svc['deploy']=copy.deepcopy(svc.get('deploy',{}));svc['deploy']['replicas']=0;d['services'][spec['source_service']]=svc
spec.update(original_sha256=hashlib.sha256(raw).hexdigest(),base_files=files[:-1],target_file=target,source_project=l['com.docker.compose.project'])
out=local/'disable';out.mkdir(exist_ok=True);(out/'spec.json').write_text(json.dumps(spec));(out/'replacement-gate.json').write_text(json.dumps(gate));(out/'proposed.yml').write_text(yaml.safe_dump(d,sort_keys=False));(out/'before.yml').write_bytes(raw)
root='/home/bekzat/refine-'+key+'-disable-20261006';ssh(main,'umask 077; mkdir -p '+root)
subprocess.run(['scp','-q',*map(str,[out/'spec.json',out/'replacement-gate.json',out/'proposed.yml',p/'disable-remote.py']),main+':'+root+'/'],check=True)
result=ssh(main,'python3 '+root+'/disable-remote.py '+root);(out/'status.json').write_bytes(result);print(result.decode())
