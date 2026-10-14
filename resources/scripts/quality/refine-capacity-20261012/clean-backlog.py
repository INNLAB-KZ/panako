"""Validate a backed-up duplicate manifest; production deletion requires explicit apply."""
import argparse
import collections
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env, pairs

os.umask(0o077)
parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['plan', 'apply'])
parser.add_argument('--max-groups', type=int)
args = parser.parse_args()
root = pathlib.Path('/home/bekzat/refine-capacity-20261012/backlog')
summary = json.loads((root / 'summary.json').read_text())
assert summary['snapshot_sha256'] == 'f9bda9a9ad14dda8c164feada4b26ce9a24e1da723aad6a8fe5ccd5175e4f083'
assert summary['plan_sha256'] == '6bd03b94f74bc2a168cc89d3f7d7bc675cb4acc7100c4ed8e4e39f36981fe601'
for name, key in [('snapshot.jsonl', 'snapshot_sha256'), ('duplicates.jsonl', 'plan_sha256')]:
    with (root / name).open('rb') as source:
        assert hashlib.file_digest(source, 'sha256').hexdigest() == summary[key]

groups = collections.OrderedDict()
removed = set()
expected_hashes = {}
with (root / 'duplicates.jsonl').open() as source:
    for line in source:
        item = json.loads(line)
        assert item['id'] not in removed
        removed.add(item['id'])
        groups.setdefault(item['keep_id'], []).append(item['id'])
        expected_hashes[item['id']] = item['entry_sha256']
        assert tuple(map(int, item['id'].split('-'))) > tuple(map(int, item['keep_id'].split('-')))
assert not removed.intersection(groups)
assert len(removed) == summary['exact_field_duplicates']
keepers = {}
actual_hashes = {}
with (root / 'snapshot.jsonl').open() as source:
    for line in source:
        mid, fields = json.loads(line)
        field_map = pairs(fields)
        signature = json.dumps(sorted(field_map.items()), separators=(',', ':'), ensure_ascii=False)
        digest = hashlib.sha256(signature.encode()).hexdigest()
        if mid in groups:
            keepers[mid] = fields
            actual_hashes[mid] = digest
        if mid in expected_hashes:
            assert digest == expected_hashes[mid]
assert len(keepers) == len(groups)
for keeper, ids in groups.items():
    assert len(ids) <= 100 and all(expected_hashes[mid] == actual_hashes[keeper] for mid in ids)

c = json.loads(subprocess.check_output([
    'docker', 'inspect', 'refine-f2-move-20261004-refine-from-main-1']))[0]
settings = env(c)
assert settings['REDIS_HOST'] == '10.0.0.2'
r = Redis(settings)
lua = (root.parent / 'delete-exact-duplicates.lua').read_text()
stream = summary['stream']
group = 'panako-worker-refine'
out = {'mode': args.mode, 'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
       'manifest_duplicates': len(removed), 'manifest_groups': len(groups),
       'evaluated_groups': 0, 'eligible': 0, 'deleted': 0, 'reasons': {}}
items = list(groups.items())
if args.mode == 'plan':
    # Read-only Lua evaluates representative early, middle and late anchors.
    indexes = sorted(set(list(range(min(5, len(items))))
        + list(range(len(items) // 2, min(len(items), len(items) // 2 + 5)))
        + list(range(max(0, len(items) - 5), len(items)))))
    items = [items[i] for i in indexes]
elif args.max_groups is not None:
    assert args.max_groups > 0
    items = items[:args.max_groups]

journal_name = 'dry-run.jsonl' if args.mode == 'plan' else 'deletion-journal.jsonl'
with (root / journal_name).open('a') as journal:
    for keeper, ids in items:
        request = {'keep_id': keeper, 'ids': ids, 'fields': keepers[keeper]}
        result = json.loads(r.cmd('EVAL', lua, 1, stream, group, args.mode, json.dumps(request)))
        out['evaluated_groups'] += 1
        out['eligible'] += result['eligible']
        out['deleted'] += result['deleted']
        out['reasons'][result['reason']] = out['reasons'].get(result['reason'], 0) + 1
        journal.write(json.dumps({'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'keep_id': keeper, **result}) + '\n')
        journal.flush()
        if out['evaluated_groups'] % 500 == 0:
            os.fsync(journal.fileno())
            time.sleep(.1)
    os.fsync(journal.fileno())
out['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
name = 'dry-run-summary.json' if args.mode == 'plan' else 'deletion-summary.json'
temp = root / (name + '.tmp')
temp.write_text(json.dumps(out, indent=2))
temp.replace(root / name)
print(json.dumps(out))
