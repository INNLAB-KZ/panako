"""Persist zero replicas for only the stalled legacy storage services."""
import copy,json,os,pathlib,re,subprocess
os.umask(0o077);p=pathlib.Path('/home/bekzat/storage-recovery-20261014')
cs=json.loads((p/'before-private.json').read_text());labels=cs[0]['Config']['Labels']
files=labels['com.docker.compose.project.config_files'].split(',');target=pathlib.Path(files[0]);names={c['Config']['Labels']['com.docker.compose.service'] for c in cs}
cmd=['docker','compose']
for f in files:cmd+=['-f',f]
cmd+=['config','--format','json']
def config():
 x=subprocess.run(cmd,capture_output=True,text=True);assert x.returncode==0,'Compose validation failed';return json.loads(x.stdout)
before=config();raw=target.read_text();backup=p/'legacy-compose-before-private.yml'
assert not (p/'legacy-compose-retired.json').exists(),'Already retired'
if backup.exists(): assert backup.read_text()==raw,'Original config changed'
backup.write_text(raw);new=raw
for name in names:
 pattern=r'(?ms)^  '+re.escape(name)+r':[^\n]*\n(?:(?!^  [\w-]+:).)*'
 match=re.search(pattern,new);assert match,name
 block=match.group()
 if re.search(r'^    deploy:\s*$',block,re.M):
  if re.search(r'^      replicas:',block,re.M):block=re.sub(r'^      replicas:.*$', '      replicas: 0',block,flags=re.M)
  else:block=re.sub(r'(^    deploy:\s*\n)',r'\1      replicas: 0\n',block,flags=re.M)
 else:
  deploy=copy.deepcopy(before['services'][name].get('deploy',{}));deploy['replicas']=0
  block=block.replace('\n','\n    deploy: '+json.dumps(deploy)+'\n',1)
 new=new[:match.start()]+block+new[match.end():]
try:
 target.write_text(new);after=config();expected=copy.deepcopy(before)
 for name in names:expected['services'][name].setdefault('deploy',{})['replicas']=0
 for name in names:
  for key in ('resources','placement'):
   if key not in expected['services'][name]['deploy'] and after['services'][name]['deploy'].get(key)=={}:
    expected['services'][name]['deploy'][key]={}
 if after!=expected:
  def differences(a,b,path=''):
   if isinstance(a,dict) and isinstance(b,dict):
    for key in a.keys()|b.keys():
     if key not in a or key not in b: print('changed_key',path+'/'+key)
     else:differences(a[key],b[key],path+'/'+key)
   elif a!=b: print('changed_value_path',path)
  differences(expected,after)
  raise AssertionError('Unexpected rendered config difference')
except BaseException:
 target.write_text(raw);raise
(p/'legacy-compose-retired.json').write_text(json.dumps({'services':sorted(names),'file':str(target)}))
print(json.dumps({'retired_service_replicas':{n:0 for n in names},'other_settings_unchanged':True}))
