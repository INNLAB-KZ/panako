"""Temporary, authenticated TLS transfer of one exact image over private LAN."""
import hashlib
import http.server
import ipaddress
import json
import os
import pathlib
import secrets
import ssl
import subprocess
import sys
import threading
import time
import urllib.request

os.umask(0o077)
p = pathlib.Path('/home/bekzat/host-isolation-20261013')
image_id = 'sha256:fe70975a3fddd3830a46a6d43bbdc2c1e15ea5f1408b4c78766fedf8eae7899c'
expected_sha = '194471bec28e2f63425681e4e95278e748cb3da972bd47f67f9a9b572839bb56'
archive = p / 'source-image.tar.gz'
if sys.argv[1] == 'serve':
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected_sha
    ip = json.loads(subprocess.check_output(['ip', '-j', 'route', 'get', '10.0.1.2']))[0]['prefsrc']
    assert ipaddress.ip_address(ip) in ipaddress.ip_network('10.0.0.0/8')
    cert, key = p / 'transfer-cert.pem', p / 'transfer-key.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
        '-nodes', '-days', '1', '-subj', '/CN=' + ip, '-addext', 'subjectAltName=IP:' + ip,
        '-keyout', str(key), '-out', str(cert)], check=True, capture_output=True)
    route = '/' + secrets.token_hex(32) + '/image'
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            if not secrets.compare_digest(self.path, route):
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Length', str(archive.stat().st_size))
            self.end_headers()
            try:
                with archive.open('rb') as f:
                    while chunk := f.read(512 * 1024):
                        self.wfile.write(chunk)
                        time.sleep(1 / 32)  # At most 16 MiB/s per download.
            except (BrokenPipeError, ConnectionResetError):
                pass
    server = http.server.ThreadingHTTPServer((ip, 18443), Handler)
    server.daemon_threads = True
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(str(cert), str(key))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    (p / 'transfer-private.json').write_text(json.dumps({'url': 'https://' + ip + ':18443' + route,
        'certificate': cert.read_text(), 'sha256': expected_sha, 'size': archive.stat().st_size}))
    (p / 'transfer-server.pid').write_text(str(os.getpid()))
    timer = threading.Timer(240, server.shutdown)
    timer.daemon = True
    timer.start()
    try:
        server.serve_forever()
    finally:
        server.server_close()
        timer.cancel()
elif sys.argv[1] == 'receive':
    d = json.loads((p / 'transfer-private.json').read_text())
    assert d['sha256'] == expected_sha
    context = ssl.create_default_context(cadata=d['certificate'])
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    digest = hashlib.sha256()
    temporary = archive.with_suffix('.part')
    with opener.open(d['url'], timeout=30) as response, temporary.open('wb') as f:
        assert response.status == 200
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            f.write(chunk)
    assert temporary.stat().st_size == d['size'] and digest.hexdigest() == expected_sha
    temporary.replace(archive)
    loaded = subprocess.run(['docker', 'image', 'load', '-i', str(archive)], capture_output=True, text=True)
    (p / 'image-load.log').write_text(loaded.stdout + loaded.stderr)
    assert loaded.returncode == 0
    image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image_id]))[0]
    assert image['Id'] == image_id
    print(json.dumps({'loaded_image': image_id, 'sha256': digest.hexdigest(), 'bytes': archive.stat().st_size}))
else:
    raise ValueError('Expected serve or receive')
