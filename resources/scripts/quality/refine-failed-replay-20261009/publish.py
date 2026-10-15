"""Prepare a two-result publication plan. --publish requires explicit approval."""
import datetime, hashlib, json, os, pathlib, sys
from redis_state import Redis, pairs

os.umask(0o077)
p=pathlib.Path('/home/bekzat/refine-failed-replay-20261009')
state=json.loads((p/'status.json').read_text())
assert state['phase']=='ready_for_review' and len(state['results'])==2
requests=json.loads((p/'failed-requests.json').read_text())
r=Redis(json.loads((p/'replay-environment.json').read_text()))
stream='panako.monitor.refine.response'
end=pairs(r.cmd('XINFO','STREAM',stream))['last-generated-id']
cursor='1791526794855-0';recent={};scanned=0
while True:
    rows=r.cmd('XRANGE',stream,cursor,end,'COUNT',500)
    if not rows:break
    for eid,raw in rows:
        fields=pairs(raw)
        try:body=json.loads(fields.get('value',fields.get('payload','{}')))
        except (ValueError,TypeError):continue
        rid=body.get('recording_id',fields.get('key'))
        if rid in [x['recording_id'] for x in requests]:recent[rid]={'id':eid,'status':body.get('status')}
    scanned+=len(rows);assert scanned<10000,'Response preflight scan exceeded bound'
    if rows[-1][0]==end:break
    cursor='('+rows[-1][0]
plan=[]
for request,result in zip(requests,state['results']):
    assert request['recording_id']==result['recording_id']
    entry=r.cmd('XRANGE','panako.monitor.refine.request',request['original_message_id'],request['original_message_id'])
    assert len(entry)==1 and pairs(entry[0][1])==request['fields'],'Original request changed'
    raw=(p/result['response_file']).read_bytes();assert hashlib.sha256(raw).hexdigest()==result['response_sha256']
    body=json.loads(raw);assert body['status']=='ok' and body['recording_id']==request['recording_id']
    assert not r.cmd('XPENDING','panako.monitor.refine.request','panako-worker-refine',request['original_message_id'],request['original_message_id'],1)
    latest=recent.get(request['recording_id']);assert latest,'Missing previous response'
    plan.append({**result,'previous_response':latest,'action':'skip_already_ok' if latest['status']=='ok' else 'publish_verified_response'})
(p/'publication-plan.json').write_text(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'results':plan},indent=2))
if sys.argv[1:]==['--publish']:
    logpath=p/'publication.json'
    log=json.loads(logpath.read_text()) if logpath.exists() else {'results':[]}
    for item in plan:
        if item['action']=='skip_already_ok' or any(x['recording_id']==item['recording_id'] for x in log['results']):continue
        payload=(p/item['response_file']).read_text()
        # Append only. No trimming, request mutation, ACK, claim or deletion.
        eid=r.cmd('XADD',stream,'*','key',item['recording_id'],'value',payload)
        log['results'].append({'recording_id':item['recording_id'],'response_id':eid,'response_sha256':item['response_sha256']})
        tmp=p/'publication.tmp';tmp.write_text(json.dumps(log,indent=2));tmp.replace(logpath)
    print(json.dumps(log))
else:
    assert not sys.argv[1:]
    print(json.dumps({'published':False,'plan':plan}))
