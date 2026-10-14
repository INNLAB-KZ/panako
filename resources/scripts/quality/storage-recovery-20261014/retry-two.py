"""Redeliver only the two requests that failed during this recovery's network preflight gap."""
import datetime,json,os,pathlib,sys
os.umask(0o077);p=pathlib.Path('/home/bekzat/storage-recovery-20261014');sys.path.insert(0,str(p));from redis_state import Redis,env,pairs
r=Redis(env(json.loads((p/'before-private.json').read_text())[0]))
assert not (p/'retry-two.json').exists(),'Do not enqueue twice'
rows=json.loads((p/'retry-requests-private.json').read_text());errors=json.loads((p/'initial-results-private.json').read_text())
assert len(rows)==len(errors)==2
error_ids=set()
for _,fs in errors:
 fs=pairs(fs);d=json.loads(fs['value']);assert d['status']=='error';error_ids.add(fs['key'])
assert {pairs(fs)['key'] for _,fs in rows}==error_ids
receipt={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'entries':[]}
# Preserve bodies exactly. No original entries/results are changed or deleted.
for old_id,fs in rows:
 new_id=r.cmd('XADD','panako-store-requests','*',*fs)
 receipt['entries'].append({'source_id':old_id,'retry_id':new_id,'key':pairs(fs)['key']})
 (p/'retry-two.json').write_text(json.dumps(receipt,indent=2))
 # Make these failed requests eligible for ordinary reclaim immediately.
 assert r.cmd('XCLAIM','panako-store-requests','panako-workers','storage-network-recovery',0,new_id,'IDLE',600001,'FORCE','JUSTID')==[new_id]
print(json.dumps(receipt))
