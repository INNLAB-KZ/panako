import os,pathlib,json,subprocess
os.umask(0o077);p=pathlib.Path('/home/bekzat/refine-lease-canary-b-20261009');d=json.loads((p/'status.json').read_text());assert d['phase']=='running';assert not (p/'observer-pids.json').exists();ids={}
for f,args in [('observe.py',[str(p)]),('watch-tasks.py',[]),('watch-source.py',[])]:
 with (p/(f+'.log')).open('ab') as log:
  proc=subprocess.Popen(['python3',str(p/f),*args],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True);ids[f]=proc.pid
(p/'observer-pids.json').write_text(json.dumps(ids));print(json.dumps(ids))
