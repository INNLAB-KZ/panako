"""Build isolated-pair images from the exact deployed JAR plus validated class overlays."""
import hashlib,json,os,pathlib,subprocess,zipfile
os.umask(0o077);p=pathlib.Path('/home/bekzat/latency-20261012');release=p/'release';release.mkdir(mode=0o700,exist_ok=True)
assert not (p/'deployment-status.json').exists(),'Release already deployed or deploying; preserve rollback image IDs'
assert hashlib.sha256((p/'panako.jar').read_bytes()).hexdigest()=='60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
classes={
 'be/panako/strategy/olaf/OlafStrategy.class':'6bd6f5b6962f41c722b315211c68d0aeb3a1d6edfc8194fb4ba763970603aef6',
 'be/panako/strategy/olaf/OlafMatchBuckets.class':'75a53f41aaa5edf7de9c7e9545e107a2ea962a0c0c2d27f1c1c17e1d57a053bb'}
data={name:(p/'cache-candidate-classes'/name).read_bytes() for name in classes}
for name,content in data.items():assert hashlib.sha256(content).hexdigest()==classes[name]
jar=release/'panako.jar'
with zipfile.ZipFile(p/'panako.jar') as source,zipfile.ZipFile(jar,'w') as target:
 for item in source.infolist():
  if item.filename not in data:target.writestr(item,source.read(item.filename))
 for name,content in data.items():target.writestr(name,content,compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(p/'panako.jar') as source,zipfile.ZipFile(jar) as target:
 for name in source.namelist():
  if name not in data:assert source.read(name)==target.read(name)
(release/'Dockerfile').write_text('ARG BASE_IMAGE=ozen-panako:latency-base-stage1-20261012\nFROM ${BASE_IMAGE}\nCOPY panako.jar /app/panako.jar\n')
(release/'.dockerignore').write_text('*\n!Dockerfile\n!panako.jar\n')
out={'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'classes':classes,'images':{},'old_images':{}}
for role in ['stage1','refine']:
 c=json.loads(subprocess.check_output(['docker','inspect','panako-isolated-pair-'+role+'-1']))[0];base=c['Image'];out['old_images'][role]=base
 base_tag='ozen-panako:latency-base-'+role+'-20261012'
 subprocess.check_call(['docker','tag',base,base_tag])
 assert json.loads(subprocess.check_output(['docker','image','inspect',base_tag]))[0]['Id']==base
 tag='ozen-panako:isolated-latency-'+role+'-20261012'
 log=subprocess.run(['docker','build','--pull=false','--network=none','--build-arg','BASE_IMAGE='+base_tag,'-t',tag,str(release)],capture_output=True,text=True)
 (p/('build-'+role+'.log')).write_text(log.stdout+log.stderr);assert log.returncode==0,'Image build failed; protected log retained'
 out['images'][role]=json.loads(subprocess.check_output(['docker','image','inspect',tag]))[0]['Id']
(p/'release.json').write_text(json.dumps(out,indent=2));print(json.dumps(out))
