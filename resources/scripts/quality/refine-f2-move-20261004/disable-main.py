import pathlib,json,subprocess,hashlib,copy,os,datetime,sys
os.umask(0o077);root=pathlib.Path('/home/bekzat/refine-f2-disable-20261004');target=pathlib.Path('/home/bekzat/project/docker-compose.audio-origin-f9b74f6.yml')
def inspect(n):return json.loads(subprocess.check_output(['docker','inspect',n]))[0]
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
c=inspect('project-panako-refine-f-1');assert c['Id']=='9e0444303e9e1130404ed604041da3179f5884e294048b2dbd23085bf30eaa3b'
e=dict(x.split('=',1) for x in c['Config']['Env']);assert e['PANAKO_WORKER_MODE']=='refine'
expected=json.loads((root/'expected.json').read_text());assert hashlib.sha256(target.read_bytes()).hexdigest()==expected['original_sha256']
base=['docker','compose','--project-name','project','-f','/home/bekzat/project/docker-compose.yml']
a=json.loads(subprocess.check_output(base+['-f',str(target),'config','--format','json']))
b=json.loads(subprocess.check_output(base+['-f',str(root/'proposed.yml'),'config','--format','json']))
want=copy.deepcopy(a);svc=want['services']['panako-refine-f'];svc['restart']='no';svc.setdefault('deploy',{})['replicas']=0;svc['deploy'].setdefault('resources',{});svc['deploy'].setdefault('placement',{})
assert want==b, 'Unexpected effective compose changes'
print('Validated: only panako-refine-f restart policy and replicas change',flush=True)
if '--apply' not in sys.argv:sys.exit(0)
gate=json.loads((root/'replacement-gate.json').read_text());assert gate['passed'] and gate['host']=='167.233.105.218' and gate['completed']>=2
assert (datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(gate['at'])).total_seconds()<300
allc=json.loads(subprocess.check_output(['docker','inspect',*subprocess.check_output(['docker','ps','-q'],text=True).split()]))
other={v['Name']:v['Id'] for v in allc if v['Id']!=c['Id']}
(root/'before.inspect.json').write_text(json.dumps(c));(root/'before.yml').write_bytes(target.read_bytes())
d={'phase':'stopping','started_at':utc(),'source_id':c['Id'],'replacement':gate};(root/'status.json').write_text(json.dumps(d,indent=2))
tmp=target.with_name(target.name+'.tmp-20261004');tmp.write_bytes((root/'proposed.yml').read_bytes());os.chmod(tmp,target.stat().st_mode & 0o777);os.replace(tmp,target)
subprocess.run(['docker','update','--restart=no',c['Id']],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['docker','stop','--timeout','30',c['Id']],check=True,stdout=subprocess.DEVNULL)
n=inspect(c['Id']);assert not n['State']['Running'] and n['HostConfig']['RestartPolicy']['Name']=='no'
assert all(inspect(k)['Id']==v and inspect(k)['State']['Running'] for k,v in other.items())
assert json.loads(subprocess.check_output(base+['-f',str(target),'config','--format','json']))==want
d.update(phase='stopped',finished_at=utc(),other_containers_unchanged=True);(root/'status.json').write_text(json.dumps(d,indent=2));print(json.dumps(d))
