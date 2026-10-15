"""Revalidate prepared refine images with a persistent tag per service."""
import concurrent.futures, json, os, pathlib, subprocess

os.umask(0o077)
scripts = pathlib.Path(__file__).resolve().parent
folder = scripts.parents[3] / 'build/latency-20261012/fleet'
assert json.loads((folder / 'rollout.json').read_text())['phase'] == 'needs_review'
prepared = json.loads((folder / 'preparation.json').read_text())
hosts = [h for h, d in prepared.items() if any(g.get('role') == 'refine' for g in d['groups'])]
remote = '/home/bekzat/latency-fleet-20261012'

def run(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=300)
    assert result.returncode == 0, 'Image repair failed: inspect protected remote preparation logs'
    return result.stdout

def repair(host):
    run(['scp', '-q', str(scripts / 'fleet-release.py'), 'bekzat@' + host + ':' + remote + '/'])
    groups = []
    for group in prepared[host]['groups']:
        if group.get('role') != 'refine': continue
        plan = json.loads(run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20', 'bekzat@' + host,
            'python3 ' + remote + '/fleet-release.py prepare ' + group['project'] + ' ' + group['service']]))
        assert plan['count'] == group['count'] and plan['role'] == 'refine'
        groups.append({**group, 'image': plan['image'], 'image_tag': plan['image_tag']})
    return host, groups

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for future in concurrent.futures.as_completed([pool.submit(repair, h) for h in hosts]):
        host, groups = future.result()
        updates = {(g['project'], g['service']): g for g in groups}
        prepared[host]['groups'] = [updates.get((g['project'], g['service']), g) for g in prepared[host]['groups']]
        temp = folder / 'preparation-repair.tmp'
        temp.write_text(json.dumps(prepared, indent=2))
        temp.replace(folder / 'preparation.json')
        print(json.dumps({'host': host, 'revalidated_service_images': len(groups)}), flush=True)
