import subprocess,json,re
raw=subprocess.check_output(['docker','ps','-aq'],text=True).split();cs=json.loads(subprocess.check_output(['docker','inspect',*raw]));out=[]
for c in cs:
 if c['Config']['Image']!='local/ozen-panako:jdbc-timeout-20261002-60a75e63':continue
 s=subprocess.check_output(['docker','logs','--since','30m','--tail','30000',c['Id']],stderr=subprocess.STDOUT,text=True)
 out.append({'name':c['Name'].lstrip('/'),'running':c['State']['Running'],'restarts':c['RestartCount'],'oom_killed':c['State']['OOMKilled'],'errors':{k:s.count(k) for k in ['Redis monitor failed','Redis worker error','Waiting for cancelled','Redis task ownership lost','java.lang.OutOfMemoryError','SocketTimeoutException','Window processing failed']},'completed':len(re.findall('Redis monitor completed:',s))})
print(json.dumps(out))
