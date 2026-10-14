"""Read-only monitor inventory and bounded log counters, without secrets."""
import datetime, json, pathlib, re, subprocess, urllib.parse

def run(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)

ids = run(['docker', 'ps', '-q']).split()
containers = json.loads(run(['docker', 'inspect', *ids])) if ids else []
out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'workers': []}
for c in containers:
    e = dict(x.split('=', 1) for x in c['Config']['Env'])
    role = e.get('PANAKO_WORKER_MODE')
    if (role not in ('stage1', 'refine') or e.get('REDIS_ENABLED', '').lower() != 'true'
            or e.get('KAFKA_MODE', '').upper() == 'STORE' or '-store-' in c['Name']):
        continue
    logs = run(['docker', 'logs', '--since', '4h', '--tail', '100000', c['Id']])
    times = list(map(int, re.findall(r'Redis monitor completed: .*? in (\d+)ms', logs)))
    labels = c['Config'].get('Labels') or {}
    jdbc = e.get('OLAF_CLICKHOUSE_URL', '')
    endpoint = urllib.parse.urlsplit('http:' + jdbc[jdbc.index('//'):]) if '//' in jdbc else None
    out['workers'].append({
        'name': c['Name'].lstrip('/'), 'id': c['Id'], 'role': role,
        'image': c['Image'], 'jar_sha256': run(['docker', 'exec', c['Id'], 'sha256sum', '/app/panako.jar']).split()[0],
        'redis_host': e.get('REDIS_HOST'), 'db_host': e.get('CLICKHOUSE_HOST'),
        'jdbc_host': endpoint.hostname if endpoint else None, 'jdbc_port': endpoint.port if endpoint else None,
        'windows': e.get('MONITOR_PARALLEL_WINDOWS'), 'threads': e.get('REDIS_WORKER_THREADS'),
        'heap': re.findall(r'-Xmx\S+', e.get('JAVA_TOOL_OPTIONS', '') + ' ' + e.get('JDK_JAVA_OPTIONS', '')),
        'memory_mib': c['HostConfig']['Memory'] / 1024 ** 2,
        'cpus': c['HostConfig']['NanoCpus'] / 1e9, 'restarts': c['RestartCount'], 'oom': c['State']['OOMKilled'],
        'project': labels.get('com.docker.compose.project'), 'service': labels.get('com.docker.compose.service'),
        'files': labels.get('com.docker.compose.project.config_files'), 'mount_count': len(c['Mounts']),
        'port_args': [a for a in c['Config']['Cmd'] or [] if a.startswith('SERVER_PORT=')],
        'completed_4h': len(times), 'max_ms_4h': max(times, default=0),
        'downloads': {k: logs.count(k) for k in ['audio_download_succeeded', 'Download attempt',
            'Download succeeded on attempt', 'audio_origin_download_failed', 'Incomplete download']},
        'errors': {k: logs.count(k) for k in ['Redis monitor failed', 'Redis worker error',
            'Redis task heartbeat failed', 'Redis task ownership lost', 'Redis task not acknowledged',
            'java.lang.OutOfMemoryError', 'Window processing failed', 'SocketTimeoutException']}})
memory = {s.split(':')[0]: int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()}
out['available_gib'] = round(memory['MemAvailable'] / 1024 ** 2, 2)
out['cpu_count'] = __import__('os').cpu_count()
print(json.dumps(out))
