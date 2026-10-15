import datetime,math,json,pathlib

def evaluate(w,samples,network):
 reasons=[];warnings=[]
 if not w.get('finished_at'):reasons.append('observation_not_finished')
 elif (datetime.datetime.fromisoformat(w['finished_at'])-datetime.datetime.fromisoformat(w['started_at'])).total_seconds()<1800:reasons.append('observation_too_short')
 if w.get('alerts'):reasons.append('monitor_alerts')
 workers=w.get('workers',[]);responses=w.get('responses',[])
 if not workers or len(samples)<90:reasons.append('insufficient_samples')
 names={x['name'] for x in workers}
 for name in names:
  done=[r for r in responses if r.get('worker')==name]
  if len(done)<2:reasons.append('insufficient_completions:'+name)
 deliveries={}
 for sample in samples:
  for x in sample.get('workers',[]):
   if not x.get('health') or not x.get('running') or x.get('restarts') or x.get('oom_killed') or any(x.get('errors',{}).values()) or len(x.get('pending',[]))>1:reasons.append('worker_failure:'+x['name'])
 for sample in samples:
  for x in sample.get('workers',[]):
   for task in x.get('pending',[]):
    previous=deliveries.get(task['id'])
    current=(task.get('consumer'),task.get('deliveries'))
    if previous is not None and current!=previous:reasons.append('unexpected_redelivery:'+task['id'])
    if task.get('idle_ms',0)>90000:reasons.append('heartbeat_idle_exceeds_90s:'+task['id'])
    deliveries[task['id']]=current
 hourly=[];normalized=[]
 for r in responses:
  if r.get('status')!='ok':reasons.append('response_not_ok');continue
  duration=r.get('duration_seconds');elapsed=r.get('processing_time_ms')
  if not duration or elapsed is None:reasons.append('response_missing_timing');continue
  elapsed=elapsed/1000;norm=elapsed*3600/duration;normalized.append(norm)
  if 3300<=duration<=3900:hourly.append(elapsed)
  if duration>=3300 and norm>600:reasons.append('normalized_processing_exceeds_10m:'+r['recording_id'])
 # Conservative task-service upper bounds include download, response publication,
 # and ACK. Exclude tasks already present in the first sample (unknown start).
 spans={}
 for i,sample in enumerate(samples):
  for worker in sample.get('workers',[]):
   for task in worker.get('pending',[]):
    rid=task.get('recording_id')
    if rid:spans.setdefault(rid,[i,i])[1]=i
 end_to_end=[]
 for response in responses:
  rid=response.get('recording_id');duration=response.get('duration_seconds',0)
  if not (duration and 3300<=duration<=3900) or rid not in spans:continue
  first,last=spans[rid]
  if first==0 or last+1>=len(samples):continue
  begin=samples[first-1].get('at');end=samples[last+1].get('at')
  if not begin or not end:continue
  upper=(datetime.datetime.fromisoformat(end)-datetime.datetime.fromisoformat(begin)).total_seconds()
  end_to_end.append(upper)
  if upper>600:reasons.append('hourly_task_service_upper_bound_exceeds_10m:'+rid)
 if len(end_to_end)<len(names):reasons.append('insufficient_task_service_timings')
 if end_to_end and sum(end_to_end)/len(end_to_end)>480:reasons.append('hourly_task_service_mean_exceeds_8m')
 if len(hourly)<len(names):reasons.append('insufficient_hourly_recordings')
 if hourly and max(hourly)>600:reasons.append('hourly_processing_exceeds_10m')
 if hourly and sum(hourly)/len(hourly)>480:reasons.append('hourly_mean_exceeds_8m')
 if hourly and max(hourly)>480:warnings.append('some_hourly_recordings_exceed_8m')
 if len(network)<30:reasons.append('insufficient_network_samples')
 for sample in network:
  if sample.get('tcp_scope')!='managed_containers':reasons.append('wrong_tcp_measurement_scope')
  if sample.get('collection_error') or set(sample.get('containers',{}))!=names:reasons.append('incomplete_container_network_sample')
 if len(network)>1:
  first,last=network[0],network[-1]
  sent=last['tcp'].get('OutSegs',0)-first['tcp'].get('OutSegs',0);retr=last['tcp'].get('RetransSegs',0)-first['tcp'].get('RetransSegs',0)
  ratio=retr/max(sent,1)
  if retr<0 or sent<0:reasons.append('network_counters_reset')
  if sent>1000 and ratio>.01:reasons.append('tcp_retransmissions_exceed_1pct')
  for name,n in last['interfaces'].items():
   if name not in first['interfaces']:continue
   f=first['interfaces'][name]
   if any(n[k]>f[k] for k in ['rx_dropped','tx_dropped','rx_errors','tx_errors']):reasons.append('interface_drops_or_errors:'+name)
 else:ratio=None
 streaks={}
 for prev,cur in zip(network,network[1:]):
  dt=(datetime.datetime.fromisoformat(cur['at'])-datetime.datetime.fromisoformat(prev['at'])).total_seconds() if 'at' in cur and 'at' in prev else 10
  for name,n in cur['interfaces'].items():
   f=prev['interfaces'].get(name);speed=n.get('speed_mbps')
   if not f or not speed or speed<=0 or dt<=0:continue
   rate=max(n['rx_bytes']-f['rx_bytes'],n['tx_bytes']-f['tx_bytes'])*8/dt/1e6
   streaks[name]=streaks.get(name,0)+1 if rate/speed>.9 else 0
   if streaks[name]>=5:reasons.append('network_link_saturated:'+name)
 return {'pass':not reasons,'reasons':sorted(set(reasons)),'warnings':warnings,'completed':len(responses),'hourly_count':len(hourly),'hourly_avg_seconds':sum(hourly)/len(hourly) if hourly else None,'hourly_max_seconds':max(hourly) if hourly else None,'hourly_p95_seconds':sorted(hourly)[math.ceil(len(hourly)*.95)-1] if hourly else None,'tcp_retransmission_ratio':ratio,'hourly_task_service_count':len(end_to_end),'hourly_task_service_avg_upper_seconds':sum(end_to_end)/len(end_to_end) if end_to_end else None,'hourly_task_service_max_upper_seconds':max(end_to_end) if end_to_end else None}

def read_gate(root):
 root=pathlib.Path(root)
 w=json.loads((root/'watch.json').read_text())
 samples=[json.loads(x) for x in (root/'samples.jsonl').read_text().splitlines() if x.strip()]
 net=[json.loads(x) for x in (root/'network.jsonl').read_text().splitlines() if x.strip()]
 return evaluate(w,samples,net)
