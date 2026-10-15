"""Read-only bounded snapshot and exact-entry duplicate plan; no Redis writes."""
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
root = pathlib.Path('/home/bekzat/refine-capacity-20261012/backlog')
root.mkdir(parents=True, exist_ok=True, mode=0o700)
assert not (root / 'snapshot.jsonl').exists(), 'Preserve the existing snapshot'
c = json.loads(subprocess.check_output([
    'docker', 'inspect', 'refine-f2-move-20261004-refine-from-main-1']))[0]
settings = env(c)
assert settings['REDIS_HOST'] == '10.0.0.2'
r = Redis(settings)
stream = 'panako.monitor.refine.request'
groups = [pairs(x) for x in r.cmd('XINFO', 'GROUPS', stream)]
assert [g['name'] for g in groups] == ['panako-worker-refine']
group = groups[0]
info = pairs(r.cmd('XINFO', 'STREAM', stream))
end = info['last-generated-id']
cursor = '(' + group['last-delivered-id']
keepers = {}
recordings = collections.Counter()
hours = collections.Counter()
recent = collections.Counter()
duplicate_hours = collections.Counter()
top_occurrences = collections.defaultdict(list)
count = duplicate_count = canonical_duplicates = 0
canonical_seen = set()
started = time.monotonic()
cutoff = int(time.time() * 1000) - 3600000
backup_hash = hashlib.sha256()
plan_hash = hashlib.sha256()

with (root / 'snapshot.jsonl').open('wb') as backup, (root / 'duplicates.jsonl').open('wb') as plan:
    for _ in range(350):
        rows = r.cmd('XRANGE', stream, cursor, end, 'COUNT', 1000)
        for mid, fields in rows:
            field_map = pairs(fields)
            assert len(field_map) * 2 == len(fields) and set(field_map) == {'key', 'value'}
            body = json.loads(field_map['value'])
            rid = body['recording_id']
            assert field_map['key'] == rid and body['audio_url'] and body['segments']
            # Exact fields, including raw JSON bytes. Changed segments or any
            # other payload difference always form a separate retained request.
            signature = json.dumps(sorted(field_map.items()), separators=(',', ':'), ensure_ascii=False)
            canonical = json.dumps(body, sort_keys=True, separators=(',', ':'))
            canonical_duplicates += canonical in canonical_seen
            canonical_seen.add(canonical)
            line = (json.dumps([mid, fields], separators=(',', ':'), ensure_ascii=False) + '\n').encode()
            backup.write(line)
            backup_hash.update(line)
            count += 1
            recordings[rid] += 1
            hour = datetime.datetime.fromtimestamp(int(mid.split('-')[0]) / 1000,
                datetime.timezone.utc).strftime('%Y-%m-%dT%H:00Z')
            hours[hour] += 1
            top_occurrences[rid].append(mid)
            if signature in keepers:
                duplicate_count += 1
                duplicate_hours[hour] += 1
                candidate = {'id': mid, 'keep_id': keepers[signature],
                             'entry_sha256': hashlib.sha256(signature.encode()).hexdigest()}
                line = (json.dumps(candidate, separators=(',', ':')) + '\n').encode()
                plan.write(line)
                plan_hash.update(line)
                if int(mid.split('-')[0]) >= cutoff:
                    recent['exact_duplicates_against_backlog'] += 1
            else:
                keepers[signature] = mid
            if int(mid.split('-')[0]) >= cutoff:
                recent['entries'] += 1
        if len(rows) < 1000 or rows[-1][0] == end:
            break
        cursor = '(' + rows[-1][0]
        time.sleep(.05)
    else:
        raise RuntimeError('Snapshot scan limit exceeded')
    for output in (backup, plan):
        output.flush()
        os.fsync(output.fileno())

assert count == len(keepers) + duplicate_count
summary = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'redis_host': '10.0.0.2', 'stream': stream, 'groups_at_start': groups,
    'snapshot_start_exclusive': group['last-delivered-id'], 'snapshot_end_inclusive': end,
    'entries': count, 'exact_field_duplicates': duplicate_count,
    'canonical_payload_duplicates': canonical_duplicates, 'retained_exact_entries': len(keepers),
    'unique_recordings': len(recordings), 'recent_hour': dict(recent),
    'arrival_hours': dict(sorted(hours.items())),
    'duplicate_arrival_hours': dict(sorted(duplicate_hours.items())),
    'most_repeated': [{'recording_id': rid, 'count': n, 'request_ids': top_occurrences[rid]}
                      for rid, n in recordings.most_common(5)],
    'snapshot_bytes': (root / 'snapshot.jsonl').stat().st_size,
    'snapshot_sha256': backup_hash.hexdigest(), 'plan_sha256': plan_hash.hexdigest(),
    'elapsed_seconds': round(time.monotonic() - started), 'redis_modified': False}
(root / 'summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary))
