import datetime, hashlib, json, os, pathlib, subprocess, time

os.umask(0o077)
p = pathlib.Path('/home/bekzat/refine-socket-replay-20261009')
out = p / 'output'
assert not (p / 'status.json').exists()
run = lambda a: subprocess.check_output(a, text=True, stderr=subprocess.PIPE)
e = json.loads((p / 'environment.json').read_text())
assert e['CLICKHOUSE_HOST'] == '10.0.0.12'
assert e['MONITOR_PARALLEL_WINDOWS'] == '2'
assert 'socket_rcvbuf' not in e['OLAF_CLICKHOUSE_URL']
e['OLAF_CLICKHOUSE_URL'] += ('&' if '?' in e['OLAF_CLICKHOUSE_URL'] else '?') + 'socket_rcvbuf=0&socket_sndbuf=0'
e.update(REDIS_ENABLED='false', KAFKA_ENABLED='FALSE', JAVA_TOOL_OPTIONS='-Xmx3584m')
(p / 'replay.env').write_text(''.join(k + '=' + v + '\n' for k, v in e.items()))
jar = pathlib.Path('/home/bekzat/refine-socket-probe-20261009/panako.jar')
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
assert (p / 'audio.m4a').stat().st_size == 64210427
mem = dict((x.split(':')[0], int(x.split()[1])) for x in pathlib.Path('/proc/meminfo').read_text().splitlines())
assert mem['MemAvailable'] > 8 * 1024 ** 2
source = json.loads(run(['docker', 'inspect', 'refine-lease-canary-20261007-refine-canary-1']))[0]
identifier = run(['docker', 'run', '-d', '--name', 'refine-socket-replay-20261009', '--restart=no',
    '--network=host', '--cpus=3', '--memory=4608m', '--memory-swap=4608m',
    '--env-file', str(p / 'replay.env'), '--mount', 'type=bind,src=' + str(p) + ',dst=/input,readonly',
    '--mount', 'type=bind,src=' + str(jar) + ',dst=/probe/panako.jar,readonly',
    '--mount', 'type=bind,src=' + str(out) + ',dst=/output', '--entrypoint', 'java', source['Image'],
    '--add-opens=java.base/java.nio=ALL-UNNAMED', '-Xlog:gc:file=/output/gc.log:time,uptime,level,tags',
    '-cp', '/input/classes:/probe/panako.jar', 'be.panako.http.SegmentReplayProbe',
    '/input/audio.m4a', '/output/replay', '0:3233,3254:3600']).strip()
d = {'phase': 'running', 'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'id': identifier}
start = time.monotonic()
while True:
    c = json.loads(run(['docker', 'inspect', identifier]))[0]
    d.update(elapsed_seconds=round(time.monotonic() - start), running=c['State']['Running'], oom=c['State']['OOMKilled'])
    if not c['State']['Running']:
        d.update(phase='complete' if c['State']['ExitCode'] == 0 else 'failed', exit_code=c['State']['ExitCode'])
    elif time.monotonic() - start > 1500:
        run(['docker', 'stop', '--timeout', '10', identifier]); d['phase'] = 'timeout'
    if d['phase'] == 'complete':
        actual = json.loads((out / 'replay.json').read_text())
        expected = json.loads((p / 'original-response.json').read_text())
        if isinstance(expected, list):
            assert len(expected) == 1
            expected = expected[0]
        d['matches_identical'] = actual['matches'] == expected['matches']
        d['processing_time_ms'] = actual.get('processing_time_ms')
        d['metrics'] = json.loads((out / 'replay.metrics.json').read_text())
    tmp = p / 'status.tmp'; tmp.write_text(json.dumps(d, indent=2)); tmp.replace(p / 'status.json')
    if d['phase'] != 'running': break
    time.sleep(10)
print(json.dumps(d))
