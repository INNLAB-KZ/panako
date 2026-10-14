"""Verify every retained snapshot entry and every journaled deletion, read-only."""
import collections
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys

sys.path.insert(0, '/home/bekzat/latency-fleet-20261012')
from redis_state import Redis, env, pairs

os.umask(0o077)
root = pathlib.Path('/home/bekzat/refine-capacity-20261012/backlog')
snapshot = json.loads((root / 'summary.json').read_text())
allowed = set()
keepers = set()
for line in (root / 'duplicates.jsonl').read_text().splitlines():
    item = json.loads(line)
    allowed.add(item['id'])
    keepers.add(item['keep_id'])
deleted = set()
total_deleted = 0
for line in (root / 'deletion-journal.jsonl').read_text().splitlines():
    item = json.loads(line)
    if item['deleted']:
        assert item['deleted'] == len(item['ids'])
        assert not deleted.intersection(item['ids'])
        deleted.update(item['ids'])
        total_deleted += item['deleted']
assert deleted.issubset(allowed) and not deleted.intersection(keepers)
assert len(deleted) == total_deleted <= snapshot['exact_field_duplicates']
expected = {}
with (root / 'snapshot.jsonl').open() as source:
    for line in source:
        mid, fields = json.loads(line)
        if mid not in deleted:
            signature = json.dumps(sorted(pairs(fields).items()), separators=(',', ':'), ensure_ascii=False)
            expected[mid] = hashlib.sha256(signature.encode()).hexdigest()

c = json.loads(subprocess.check_output([
    'docker', 'inspect', 'refine-f2-move-20261004-refine-from-main-1']))[0]
settings = env(c)
assert settings['REDIS_HOST'] == '10.0.0.2'
r = Redis(settings)
stream = snapshot['stream']
group = 'panako-worker-refine'
groups = [pairs(x) for x in r.cmd('XINFO', 'GROUPS', stream)]
assert [x['name'] for x in groups] == [group]
boundary = tuple(map(int, groups[0]['last-delivered-id'].split('-')))
end = pairs(r.cmd('XINFO', 'STREAM', stream))['last-generated-id']
cursor = '-'
seen = set()
unread = 0
new_entries = 0
unread_signatures = set()
unread_duplicates = 0
for _ in range(350):
    rows = r.cmd('XRANGE', stream, cursor, end, 'COUNT', 1000)
    for mid, fields in rows:
        assert mid not in deleted, 'Journaled duplicate remains in stream'
        seen.add(mid)
        signature = json.dumps(sorted(pairs(fields).items()), separators=(',', ':'), ensure_ascii=False)
        if mid in expected:
            assert hashlib.sha256(signature.encode()).hexdigest() == expected[mid], 'Retained payload changed'
        if tuple(map(int, mid.split('-'))) > boundary:
            unread += 1
            unread_duplicates += signature in unread_signatures
            unread_signatures.add(signature)
        if tuple(map(int, mid.split('-'))) > tuple(map(int, snapshot['snapshot_end_inclusive'].split('-'))):
            new_entries += 1
    if len(rows) < 1000 or rows[-1][0] == end:
        break
    cursor = '(' + rows[-1][0]
else:
    raise RuntimeError('Verification scan limit')
assert expected.keys() <= seen, 'A retained snapshot entry is missing'
pending = r.cmd('XPENDING', stream, group, '-', '+', 1000)
assert all(r.cmd('XRANGE', stream, item[0], item[0]) for item in pending)
before = json.loads((root / 'before-cleanup.json').read_text())
assert not deleted.intersection(before['pending_ids'])
assert all(r.cmd('XRANGE', stream, mid, mid) for mid in before['pending_ids'])
response_groups = [pairs(x) for x in r.cmd('XINFO', 'GROUPS', 'panako.monitor.refine.response')]
out = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'passed': True,
    'deleted': len(deleted), 'authorized_maximum': snapshot['exact_field_duplicates'],
    'authorized_duplicates_not_deleted': len(allowed - deleted),
    'retained_snapshot_entries_verified': len(expected), 'all_retained_payloads_identical': True,
    'all_deleted_ids_absent': True, 'all_original_pending_payloads_present': True,
    'current_pending_count': len(pending), 'current_pending_payloads_present': True,
    'direct_unread_count': unread, 'distinct_unread_exact_requests': len(unread_signatures),
    'remaining_unread_exact_duplicates': unread_duplicates, 'new_entries_outside_snapshot': new_entries,
    'count_start_exclusive': groups[0]['last-delivered-id'], 'count_end_inclusive': end,
    'request_groups_at_count_start': groups, 'response_groups': response_groups,
    'deletion_pass': json.loads((root / 'deletion-summary.json').read_text())}
(root / 'final-verification.json').write_text(json.dumps(out, indent=2))
print(json.dumps(out))
