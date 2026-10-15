"""Sample Main during the authorized rollout; read-only SQL and OS counters."""
import datetime, json, os, pathlib, subprocess, time

os.umask(0o077)
scripts = pathlib.Path(__file__).resolve().parent
evidence = scripts.parents[3] / 'build/latency-20261012/fleet'
while not (evidence / 'STOP-MAIN-WATCH').exists():
    try:
        result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20',
            '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=3', 'bekzat@116.202.112.101', 'python3 -'],
            input=(scripts / 'main-audit.py').read_text(), capture_output=True, text=True, timeout=120)
        assert result.returncode == 0
        snapshot = json.loads(result.stdout)
        (evidence / 'main-sampled-latest.json').write_text(json.dumps(snapshot, indent=2))
        with (evidence / 'main-samples.jsonl').open('a') as log:
            log.write(json.dumps(snapshot) + '\n')
        print(json.dumps({'at': snapshot['at'], 'host': snapshot['host'],
                          'query_errors': len(snapshot['db']['8123']['errors_15min'])}), flush=True)
    except Exception as error:
        print(json.dumps({'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          'collection_error': type(error).__name__}), flush=True)
    time.sleep(50)
