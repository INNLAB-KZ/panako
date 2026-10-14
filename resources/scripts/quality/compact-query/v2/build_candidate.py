from pathlib import Path
import subprocess,zipfile,hashlib,json
p=Path('build/compact-query-v2-20260929');(p/'src').mkdir(parents=True,exist_ok=True);(p/'classes').mkdir(exist_ok=True)
base=Path('build/libs/panako-2.1-all.jar');assert hashlib.sha256(base.read_bytes()).hexdigest()=='5d50209bf724eec120623c22e282b68de62c8b46fabd4712eed01615808db022'
source=Path('src/main/java/be/panako/strategy/olaf/storage')
for name in ['OlafStorageClickHouse','OlafQueryHashIndex','OlafPackedHitList']:
 s=(source/(name+'.java')).read_text()
 if name=='OlafStorageClickHouse':
  s=s.replace('\t\t\tthrow new IllegalStateException("Failed to store OLAF metadata", e);\n','').replace('\t\t\tthrow new IllegalStateException("Failed to store OLAF fingerprints", e);\n','')
 (p/'src'/(name+'.java')).write_text(s)
subprocess.run(['/Users/bekzat/Library/Java/JavaVirtualMachines/temurin-17.0.20/Contents/Home/bin/javac','--release','11','-cp',str(base),'-d',str(p/'classes'),*map(str,(p/'src').glob('*.java'))],check=True)
entries={str(f.relative_to(p/'classes')):f.read_bytes() for f in (p/'classes').rglob('*.class')}
with zipfile.ZipFile(base) as src,zipfile.ZipFile(p/'panako.jar','w',zipfile.ZIP_DEFLATED) as out:
 for info in src.infolist():out.writestr(info,entries.pop(info.filename,src.read(info.filename)))
 for n,data in entries.items():out.writestr(n,data)
with zipfile.ZipFile(base) as a,zipfile.ZipFile(p/'panako.jar') as b:
 changed=[n for n in a.namelist() if a.read(n)!=b.read(n)];added=sorted(set(b.namelist())-set(a.namelist()))
 assert changed==['be/panako/strategy/olaf/storage/OlafStorageClickHouse.class'],changed
 report={'sha256':hashlib.sha256((p/'panako.jar').read_bytes()).hexdigest(),'changed':changed,'added':added}
(p/'artifact.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
