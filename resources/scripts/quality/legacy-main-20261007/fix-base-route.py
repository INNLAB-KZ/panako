import pathlib,subprocess,json,yaml,copy,os,urllib.parse
os.umask(0o077)
p=pathlib.Path('/home/bekzat/legacy-main-20261007');target=pathlib.Path('/home/bekzat/project/docker-compose.yml')
def run(a):return subprocess.check_output(a,text=True,stderr=subprocess.PIPE)
cs=json.loads(run(['docker','inspect',*run(['docker','ps','-q']).split()]))
c=next(x for x in cs if x['Config']['Labels'].get('com.docker.compose.service')=='panako-api')
files=c['Config']['Labels']['com.docker.compose.project.config_files'].split(',');assert files[0]==str(target)
base=['docker','compose','--project-name','project','--project-directory',str(target.parent)]
def cfg(fs):return json.loads(run(base+sum((['-f',str(f)] for f in fs),[])+['config','--format','json']))
a=cfg([target]);layered=cfg(files);src=target.read_bytes();doc=yaml.safe_load(src)
e=doc['services']['panako-api']['environment'];e=dict(v.split('=',1) for v in e) if isinstance(e,list) else e
old=a['services']['panako-api']['environment'];url=old['OLAF_CLICKHOUSE_URL'];assert '10.0.0.23' in url
url=url.replace('10.0.0.23','10.0.1.2');e.update(CLICKHOUSE_HOST='10.0.1.2',OLAF_CLICKHOUSE_URL=url.replace('$','$$'));doc['services']['panako-api']['environment']=e
proposal=p/'base-route-proposed.yml';proposal.write_text(yaml.safe_dump(doc,sort_keys=False))
want=copy.deepcopy(a);want['services']['panako-api']['environment'].update(CLICKHOUSE_HOST='10.0.1.2',OLAF_CLICKHOUSE_URL=url)
assert cfg([proposal])==want
assert cfg([proposal]+files[1:])==layered,'Layered configuration changed'
assert target.read_bytes()==src
(p/'before-base-route.yml').write_bytes(src);tmp=target.with_name('docker-compose.route.tmp');tmp.write_bytes(proposal.read_bytes());os.chmod(tmp,0o600);os.replace(tmp,target)
assert all(json.loads(run(['docker','inspect',x['Id']]))[0]['State']['Running'] for x in cs)
print(json.dumps({'base_route':'10.0.1.2','layered_config_unchanged':True,'all_container_ids_unchanged':True}))
