"""Ten-second CPU and selected network interface deltas; no throughput probe."""
import datetime, json, pathlib, subprocess, time

def cpu():
    return list(map(int, pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))

def net(name):
    p = pathlib.Path('/sys/class/net') / name / 'statistics'
    return {k: int((p / k).read_text()) for k in ['rx_bytes', 'tx_bytes', 'rx_errors', 'tx_errors', 'rx_dropped', 'tx_dropped']}

interfaces = set()
for address in ['10.0.1.2', '1.1.1.1']:
    route = json.loads(subprocess.check_output(['ip', '-j', 'route', 'get', address]))
    interfaces.update(x['dev'] for x in route if x.get('dev'))
c0 = cpu()
n0 = {name: net(name) for name in interfaces}
start = time.monotonic()
time.sleep(10)
elapsed = time.monotonic() - start
c1 = cpu()
delta = [b - a for a, b in zip(c0, c1)]
memory = {s.split(':')[0]: int(s.split()[1]) for s in pathlib.Path('/proc/meminfo').read_text().splitlines()}
out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
       'cpu_busy_pct': round(100 * (sum(delta) - delta[3] - delta[4]) / sum(delta), 2),
       'iowait_pct': round(100 * delta[4] / sum(delta), 2),
       'available_gib': round(memory['MemAvailable'] / 1024 ** 2, 2), 'interfaces': {}}
for name in interfaces:
    new = net(name)
    d = {k: new[k] - n0[name][k] for k in new}
    out['interfaces'][name] = {'rx_mbps': round(d.pop('rx_bytes') * 8 / elapsed / 1e6, 2),
                               'tx_mbps': round(d.pop('tx_bytes') * 8 / elapsed / 1e6, 2), **d}
print(json.dumps(out))
