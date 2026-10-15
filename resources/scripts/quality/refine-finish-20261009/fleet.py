"""Inventory active monitor consumers and references to the retiring replica."""
import datetime, json, subprocess, urllib.parse
run=lambda args:subprocess.check_output(args,text=True,stderr=subprocess.PIPE)
ids=run(['docker','ps','-q']).split()
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'workers':[],'retiring_references':[],'proxies':[]}
for c in json.loads(run(['docker','inspect',*ids])) if ids else []:
    e=dict(v.split('=',1) for v in c['Config']['Env'])
    refs=[k for k,v in e.items() if 'CLICKHOUSE' in k and ('HOST' in k or 'URL' in k)
          and any(ip in v for ip in ['10.0.0.22','46.224.159.198'])]
    if refs:out['retiring_references'].append({'name':c['Name'],'keys':refs})
    mode=e.get('PANAKO_WORKER_MODE')
    if mode in ['stage1','refine'] and '-store-' not in c['Name'] and e.get('REDIS_ENABLED','').lower()!='false':
        url=e.get('OLAF_CLICKHOUSE_URL','');endpoint=urllib.parse.urlsplit('http:'+url[url.index('//'):]) if '//' in url else None
        out['workers'].append({'name':c['Name'],'id':c['Id'],'role':mode,
            'sha256':run(['docker','exec',c['Id'],'sha256sum','/app/panako.jar']).split()[0],
            'windows':e.get('MONITOR_PARALLEL_WINDOWS'),'threads':e.get('REDIS_WORKER_THREADS'),
            'heap':e.get('JAVA_TOOL_OPTIONS') or e.get('JDK_JAVA_OPTIONS'),
            'memory_bytes':c['HostConfig']['Memory'],'restarts':c['RestartCount'],'oom':c['State']['OOMKilled'],
            'db_host':endpoint.hostname if endpoint else None,'db_port':endpoint.port if endpoint else None,
            'socket_autotune':'socket_rcvbuf=0' in url,'audio_origin':bool(e.get('AUDIO_ORIGIN_BASE_URL'))})
    if any(x in (c['Name']+' '+c['Config']['Image']).lower() for x in ['haproxy','read-router']):
        configs=[m['Destination'] for m in c['Mounts'] if m['Destination'].endswith('.cfg')]
        proxy={'name':c['Name'],'configs_checked':configs,'references_retiring':False}
        for path in configs:
            content=run(['docker','exec',c['Id'],'cat',path])
            proxy['references_retiring'] |= any(ip in content for ip in ['10.0.0.22','46.224.159.198'])
        out['proxies'].append(proxy)
print(json.dumps(out))
