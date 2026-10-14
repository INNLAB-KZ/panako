"""Read-only Main host resource samples; physical and VLAN traffic kept separate."""
import datetime, json, os, pathlib, sys, time

os.umask(0o077)
p = pathlib.Path(sys.argv[1])
duration = int(sys.argv[2])
start = time.monotonic()

def snapshot():
    cpu = list(map(int, pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))
    mem = {x.split(':')[0]: int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
    network = {}
    for name in ['ens23', 'ens23.4000']:
        q = pathlib.Path('/sys/class/net') / name / 'statistics'
        network[name] = {k: int((q/k).read_text()) for k in
                         ['rx_bytes', 'tx_bytes', 'rx_errors', 'tx_errors', 'rx_dropped', 'tx_dropped']}
    return {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'mono': time.monotonic(),
            'cpu': cpu, 'available_gib': mem['MemAvailable']/1024**2, 'network': network}

previous = snapshot()
while time.monotonic() - start < duration:
    time.sleep(30)
    current = snapshot()
    elapsed = current['mono'] - previous['mono']
    delta = [a-b for a,b in zip(current['cpu'], previous['cpu'])]
    current['cpu_busy_pct'] = 100*(sum(delta)-delta[3]-delta[4])/sum(delta)
    current['iowait_pct'] = 100*delta[4]/sum(delta)
    current['rates'] = {}
    for name, stats in current['network'].items():
        diff = {k: v-previous['network'][name][k] for k,v in stats.items()}
        current['rates'][name] = {**diff, 'rx_mbps': diff['rx_bytes']*8/elapsed/1e6,
                                 'tx_mbps': diff['tx_bytes']*8/elapsed/1e6}
    with (p/'resources.jsonl').open('a') as f:
        f.write(json.dumps(current)+'\n')
    tmp=p/'resources.tmp'; tmp.write_text(json.dumps(current,indent=2)); tmp.replace(p/'resources.json')
    previous=current
