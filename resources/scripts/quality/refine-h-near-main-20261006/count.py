import subprocess,json,collections
cs=json.loads(subprocess.check_output(['docker','inspect',*subprocess.check_output(['docker','ps','-aq'],text=True).split()]));counts=collections.Counter();out=[]
for c in cs:
 e=dict(v.split('=',1) for v in c['Config']['Env']);role=e.get('PANAKO_WORKER_MODE')
 if c['State']['Running']:counts[role or 'other']+=1
 if role=='refine':out.append({'name':c['Name'],'running':c['State']['Running'],'restarts':c['RestartCount'],'oom_killed':c['State']['OOMKilled']})
print(json.dumps({'running_roles':dict(counts),'refines':out}))
