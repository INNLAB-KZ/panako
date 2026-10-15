"""Build a storage-only overlay on the verified monitor runtime, without unrelated edits."""
import hashlib, json, os, pathlib, subprocess, zipfile
os.umask(0o077)
p = pathlib.Path('build/storage-recovery-20261014')
base = pathlib.Path('build/latency-20261012/candidate.jar')
assert hashlib.sha256(base.read_bytes()).hexdigest() == '7a24e3f7e2d1be928211c1b335a09c16a39d58d5e05f61b7cb76cdafb839298b'
names = ['http/RedisTaskDispatcher', 'http/PanakoRedisStreamWorker', 'http/LongAudioDuplicateCheck', 'http/StoreResourceCleanup',
         'http/HttpUtil', 'strategy/olaf/OlafStrategy', 'strategy/olaf/storage/OlafStorageClickHouse']
source = p / 'source'; classes = p / 'classes'; classes.mkdir(exist_ok=True)
files = []
for name in names:
    dest = source / ('be/panako/' + name + '.java'); dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(pathlib.Path('src/main/java/be/panako/' + name + '.java').read_bytes()); files.append(str(dest))
java = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()
subprocess.run([java + '/bin/javac', '--release', '11', '-cp', str(base), '-sourcepath', str(source), '-d', str(classes), *files], check=True)
overlay = {str(f.relative_to(classes)): f.read_bytes() for f in classes.rglob('*.class')}
jar = p / 'panako.jar'
with zipfile.ZipFile(base) as src, zipfile.ZipFile(jar, 'w') as dest:
    for item in src.infolist():
        if item.filename not in overlay: dest.writestr(item, src.read(item.filename))
    for name, data in overlay.items(): dest.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(base) as src, zipfile.ZipFile(jar) as dest:
    for name in src.namelist():
        if name not in overlay: assert src.read(name) == dest.read(name)
manifest = {'base_sha256': hashlib.sha256(base.read_bytes()).hexdigest(), 'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
            'classes': {n: hashlib.sha256(d).hexdigest() for n,d in overlay.items()}}
(p / 'release.json').write_text(json.dumps(manifest, indent=2)); print(json.dumps(manifest))
