import pathlib,subprocess,json,sys
p=pathlib.Path('/home/bekzat/impulse-fix-20260929')
for script,result in [('audit_pair.py','fingerprint-comparison.json'),('run.py','status.json'),('quality.py','quality.json')]:
 subprocess.run([sys.executable,str(p/script)],check=True)
 r=json.loads((p/result).read_text())
 if r['phase'] not in ['passed','completed']:break
