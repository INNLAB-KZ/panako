import json,subprocess,urllib.parse,re
ids=subprocess.check_output(['docker','ps','-q'],text=True).split();out=[]
for c in json.loads(subprocess.check_output(['docker','inspect',*ids])):
 e=dict(x.split('=',1) for x in c['Config']['Env']);targets={}
 for k,v in e.items():
  if 'CLICKHOUSE' not in k or not ('HOST' in k or 'URL' in k):continue
  if 'URL' in k:
   u=urllib.parse.urlsplit(v.removeprefix('jdbc:'));targets[k]={'host':u.hostname,'port':u.port}
  else:targets[k]=v if re.fullmatch(r'[a-zA-Z0-9_.:-]+',v) else '[non-host value]'
 if targets:out.append({'name':c['Name'],'role':e.get('PANAKO_WORKER_MODE'),'targets':targets})
print(json.dumps(out))
