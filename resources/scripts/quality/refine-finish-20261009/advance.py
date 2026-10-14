"""Advance one prepared host only after its canary observation passes."""
import datetime,json,os,pathlib,subprocess,sys,time

os.umask(0o077)
canary=pathlib.Path(sys.argv[1]);wave=pathlib.Path(sys.argv[2])
state={'phase':'waiting_for_canary','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
def save():
    state['at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    tmp=wave/'advance.tmp';tmp.write_text(json.dumps(state,indent=2));tmp.replace(wave/'advance.json')
assert not (wave/'advance.json').exists() and not (wave/'status.json').exists()
save()
deadline=time.monotonic()+5400
try:
    while True:
        watch=json.loads((canary/'watch.json').read_text())
        if watch.get('finished_at'):
            assert watch.get('passed'), 'Canary observation failed; no rollout'
            break
        assert time.monotonic()<deadline, 'Canary observation timed out'
        time.sleep(15)
    state['phase']='deploying_wave';save()
    with (wave/'deploy.log').open('w') as log:
        result=subprocess.run(['python3',str(wave/'deploy.py')],stdin=subprocess.DEVNULL,stdout=log,stderr=log)
    assert result.returncode==0, 'Wave deployment failed; inspect rollback status'
    assert json.loads((wave/'status.json').read_text())['phase']=='running'
    state['phase']='observing_wave';save()
    while True:
        path=wave/'watch.json'
        if path.exists():
            watch=json.loads(path.read_text())
            if watch.get('finished'):
                assert watch.get('passed'), 'Wave observation failed; inspect alerts and recovery'
                state.update(phase='complete',all_recovered=watch['all_recovered']);save();break
        assert time.monotonic()<deadline, 'Wave observation timed out'
        time.sleep(15)
except BaseException as error:
    state.update(phase='blocked',error=str(error));save();raise
