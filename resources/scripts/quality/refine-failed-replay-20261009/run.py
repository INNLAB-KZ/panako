"""Recompute two failed-download tasks offline; never publishes Redis results."""
import datetime, hashlib, json, os, pathlib, subprocess, time

os.umask(0o077)
p = pathlib.Path('/home/bekzat/refine-failed-replay-20261009')
base = pathlib.Path('/home/bekzat/refine-replay-20261008')
run = lambda args: subprocess.check_output(args, text=True, stderr=subprocess.PIPE)
assert not (p/'status.json').exists()
mem={x.split(':')[0]:int(x.split()[1]) for x in pathlib.Path('/proc/meminfo').read_text().splitlines()}
assert mem['MemAvailable']>16*1024**2
requests = json.loads((p/'failed-requests.json').read_text())
manifest = json.loads((p/'audio-manifest.json').read_text())
assert [x['original_message_id'] for x in requests] == ['1790883408013-0','1790883409967-0']
e = json.loads((p/'replay-environment.json').read_text())
assert e['REDIS_ENABLED']=='false' and e['KAFKA_ENABLED']=='FALSE'
assert e['CLICKHOUSE_HOST']=='10.0.1.2' and 'socket_rcvbuf=0' in e['OLAF_CLICKHOUSE_URL']
assert e['MONITOR_PARALLEL_WINDOWS']=='2' and e['JAVA_TOOL_OPTIONS']=='-Xmx3584m'
assert hashlib.sha256((base/'panako.jar').read_bytes()).hexdigest() == '60a75e63755df1b7e71c0f71e57a9b4abdb2447a2823a690cbd260002df74c2e'
image = json.loads(run(['docker','inspect','refine-offline-replay-20261008']))[0]['Image']
for name, values in [('replay.env',e),('download.env',dict(e,JAVA_TOOL_OPTIONS='-Xmx256m'))]:
    (p/name).write_text(''.join(k+'='+v+'\n' for k,v in values.items()))
d = {'phase':'running','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
     'redis_publication':False,'results':[]}
def save():
    tmp=p/'status.tmp';tmp.write_text(json.dumps(d,indent=2));tmp.replace(p/'status.json')
save()

def execute(name, envfile, cpu, memory, java_args, timeout):
    cid = run(['docker','run','-d','--name',name,'--restart=no','--network=host',
         '--cpus',str(cpu),'--memory',memory,'--memory-swap',memory,'--env-file',str(p/envfile),
         '--mount','type=bind,src='+str(p)+',dst=/input,readonly',
         '--mount','type=bind,src='+str(base/'classes')+',dst=/legacy,readonly',
         '--mount','type=bind,src='+str(base/'panako.jar')+',dst=/probe/panako.jar,readonly',
         '--entrypoint','java',image,'--add-opens=java.base/java.nio=ALL-UNNAMED',
         '-cp','/input/classes:/legacy:/probe/panako.jar',*java_args]).strip()
    d.update(current_container=cid,current_name=name);save();start=time.monotonic()
    while True:
        c=json.loads(run(['docker','inspect',cid]))[0]
        if not c['State']['Running']:break
        if time.monotonic()-start>timeout:
            run(['docker','stop','--timeout','10',cid]);raise RuntimeError('Offline probe deadline exceeded')
        d['current_elapsed_seconds']=round(time.monotonic()-start);save();time.sleep(10)
    log=subprocess.run(['docker','logs','--timestamps',cid],capture_output=True,text=True)
    (p/(name+'.log')).write_text(log.stdout+log.stderr)
    assert c['State']['ExitCode']==0 and not c['State']['OOMKilled'], name+' failed'
    return cid

try:
    for index,(request,expected) in enumerate(zip(requests,manifest),1):
        fields=request['fields'];body=json.loads(fields.get('value',fields.get('payload','{}')))
        rid=body['recording_id'];assert rid==request['recording_id']
        (p/('request'+str(index)+'.url')).write_text(body['audio_url'])
        cid=execute('refine-failed-download-20261009-'+str(index),'download.env',1,'384m',
                    ['be.panako.http.DownloadProbe','/input/request'+str(index)+'.url','/tmp/audio.m4a'],420)
        audio=p/('audio'+str(index)+'.m4a');run(['docker','cp',cid+':/tmp/audio.m4a',str(audio)]);audio.chmod(0o600)
        assert audio.stat().st_size==expected['bytes'] and hashlib.sha256(audio.read_bytes()).hexdigest()==expected['sha256']
        segments=','.join(str(int(s['start_seconds']))+':'+str(int(s['end_seconds'])) for s in body['segments'])
        assert segments
        cid=execute('refine-failed-replay-20261009-'+str(index),'replay.env',3,'4608m',
                    ['be.panako.http.RecoveryReplayProbe','/input/'+audio.name,'/tmp/response.json',segments,rid],1500)
        output=p/('response'+str(index)+'.json');run(['docker','cp',cid+':/tmp/response.json',str(output)]);output.chmod(0o600)
        response=json.loads(output.read_text());assert response['status']=='ok' and response['recording_id']==rid
        d['results'].append({'recording_id':rid,'original_message_id':request['original_message_id'],
                             'processing_time_ms':response['processing_time_ms'],'duration_seconds':response['duration_seconds'],
                             'unique_tracks_count':response['unique_tracks_count'],'response_file':output.name,
                             'response_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'audio_sha256':expected['sha256']})
        save()
    d.update(phase='ready_for_review',finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat());save()
except BaseException as exc:
    d.update(phase='failed',error=type(exc).__name__);save();raise
print(json.dumps(d))
