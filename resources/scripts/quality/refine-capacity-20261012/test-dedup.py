"""Exercise deletion guards on a disposable Unix-socket Redis, never production."""
import json
import pathlib
import socket
import subprocess
import tempfile
import time

script = pathlib.Path(__file__).with_name('delete-exact-duplicates.lua').read_text()


class Redis:
    def __init__(self, path):
        self.s = socket.socket(socket.AF_UNIX)
        self.s.connect(path)
        self.f = self.s.makefile('rb')

    def cmd(self, *args):
        values = [str(x).encode() for x in args]
        self.s.sendall(b'*%d\r\n' % len(values) + b''.join(
            b'$%d\r\n' % len(v) + v + b'\r\n' for v in values))
        return self.read()

    def read(self):
        line = self.f.readline()
        kind, rest = line[:1], line[1:-2]
        if kind == b'+': return rest.decode()
        if kind == b'-': raise RuntimeError(rest.decode())
        if kind == b':': return int(rest)
        if kind == b'$':
            if int(rest) < 0: return None
            value = self.f.read(int(rest)); self.f.read(2)
            return value.decode()
        if kind == b'*': return [self.read() for _ in range(int(rest))] if int(rest) >= 0 else None
        raise RuntimeError('Unexpected Redis reply')


with tempfile.TemporaryDirectory(prefix='refine-dedup-', dir='/tmp') as directory:
    sock = directory + '/redis.sock'
    with open(directory + '/redis.log', 'w') as log:
        proc = subprocess.Popen(['/usr/local/bin/redis-server', '--port', '0',
            '--unixsocket', sock, '--unixsocketperm', '700', '--save', '',
            '--appendonly', 'no', '--dir', directory], stdout=log, stderr=log)
        try:
            for _ in range(100):
                if pathlib.Path(sock).exists(): break
                assert proc.poll() is None
                time.sleep(.05)
            r = Redis(sock)
            assert r.cmd('PING') == 'PONG'
            fields = ['key', 'r1', 'value', '{"recording_id":"r1","segments":[1,2]}']

            def fixture():
                r.cmd('FLUSHDB')
                for mid in ['1000-0', '1001-0', '1002-0']:
                    r.cmd('XADD', 'requests', mid, *fields)
                r.cmd('XADD', 'requests', '1003-0', 'key', 'r1', 'value', 'different segments')
                r.cmd('XGROUP', 'CREATE', 'requests', 'workers', '0-0')

            def invoke(mode='apply', **changes):
                item = dict(keep_id='1000-0', ids=['1001-0', '1002-0'], fields=fields)
                item.update(changes)
                return json.loads(r.cmd('EVAL', script, 1, 'requests', 'workers', mode, json.dumps(item)))

            tests = []
            fixture(); assert invoke('plan')['eligible'] == 2 and r.cmd('XLEN', 'requests') == 4
            tests.append('dry_run_does_not_modify_stream')
            assert invoke()['deleted'] == 2 and r.cmd('XLEN', 'requests') == 2
            assert r.cmd('XRANGE', 'requests', '1000-0', '1000-0')
            assert r.cmd('XRANGE', 'requests', '1003-0', '1003-0')
            tests.append('retains_one_exact_request_and_changed_segments')
            assert invoke()['deleted'] == 0
            tests.append('repeated_apply_is_idempotent')
            fixture(); r.cmd('XREADGROUP', 'GROUP', 'workers', 'consumer', 'COUNT', 1, 'STREAMS', 'requests', '>')
            assert invoke()['reason'] == 'keeper_already_delivered' and r.cmd('XLEN', 'requests') == 4
            tests.append('consumer_advancing_before_cleanup_preserves_all_entries')
            fixture(); r.cmd('XDEL', 'requests', '1000-0')
            assert invoke()['reason'] == 'keeper_missing' and r.cmd('XLEN', 'requests') == 3
            tests.append('missing_keeper_prevents_deletion')
            fixture(); r.cmd('XGROUP', 'CREATE', 'requests', 'another', '0-0')
            try: invoke(); raise AssertionError('Expected guard')
            except RuntimeError as error: assert 'group set changed' in str(error)
            assert r.cmd('XLEN', 'requests') == 4
            tests.append('additional_consumer_group_prevents_deletion')
            fixture()
            try: invoke(ids=['1001-0', '1003-0']); raise AssertionError('Expected guard')
            except RuntimeError as error: assert 'payload mismatch' in str(error)
            assert r.cmd('XLEN', 'requests') == 4
            tests.append('mismatched_payload_prevents_even_partial_deletion')
            fixture()
            r.cmd('XCLAIM', 'requests', 'workers', 'forced', 0, '1001-0', 'FORCE')
            assert invoke()['deleted'] == 1
            assert r.cmd('XPENDING', 'requests', 'workers', '1001-0', '1001-0', 1)
            assert r.cmd('XRANGE', 'requests', '1001-0', '1001-0')
            tests.append('pending_duplicate_is_preserved')
            print(json.dumps({'passed': True, 'tests': tests, 'production_access': False}))
        finally:
            proc.terminate()
            proc.wait(timeout=10)
