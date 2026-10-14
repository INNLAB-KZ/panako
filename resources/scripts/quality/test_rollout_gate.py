import unittest,copy,datetime
from rollout_gate import evaluate
class GateTest(unittest.TestCase):
 def fixture(self):
  x={'name':'worker','health':True,'running':True,'restarts':0,'oom_killed':False,'errors':{},'pending':[]}
  w={'started_at':'2026-09-24T20:00:00+00:00','finished_at':'2026-09-24T20:30:01+00:00','alerts':[],'workers':[x],'responses':[{'worker':'worker','recording_id':str(i),'status':'ok','duration_seconds':3600,'processing_time_ms':300000} for i in range(2)]}
  samples=[{'at':(datetime.datetime(2026,9,24,20,tzinfo=datetime.timezone.utc)+datetime.timedelta(seconds=i*15)).isoformat(),'workers':[copy.deepcopy(x)]} for i in range(100)]
  for rid,a,b in [('0',10,30),('1',40,60)]:
   for sample in samples[a:b]:sample['workers'][0]['pending']=[{'id':'task-'+rid,'consumer':'a','deliveries':1,'idle_ms':1000,'recording_id':rid}]
  return w,samples,[{'tcp_scope':'managed_containers','containers':{'worker':{'id':'worker-id'}},'tcp':{'OutSegs':i*10000,'RetransSegs':i},'interfaces':{}} for i in range(31)]
 def test_good(self):self.assertTrue(evaluate(*self.fixture())['pass'])
 def test_slow(self):
  w,s,n=self.fixture();w['responses'][0]['processing_time_ms']=601000;self.assertFalse(evaluate(w,s,n)['pass'])
 def test_transient_error(self):
  w,s,n=self.fixture();s[2]['workers'][0]['errors']={'OOM':1};self.assertFalse(evaluate(w,s,n)['pass'])
 def test_incomplete(self):
  w,s,n=self.fixture();w.pop('finished_at');self.assertFalse(evaluate(w,s,n)['pass'])
 def test_network_loss(self):
  w,s,n=self.fixture();n[-1]['tcp']['RetransSegs']=10000;self.assertFalse(evaluate(w,s,n)['pass'])
 def test_missing_results(self):
  w,s,n=self.fixture();w['responses']=[];self.assertFalse(evaluate(w,s,n)['pass'])
 def test_redelivery(self):
  w,s,n=self.fixture();s[0]['workers'][0]['pending']=[{'id':'1-0','consumer':'a','deliveries':1,'idle_ms':0}];s[1]['workers'][0]['pending']=[{'id':'1-0','consumer':'b','deliveries':2,'idle_ms':0}];self.assertFalse(evaluate(w,s,n)['pass'])
 def test_initial_retry_allowed(self):
  w,s,n=self.fixture();s[0]['workers'][0]['pending']=[{'id':'1-0','consumer':'a','deliveries':2,'idle_ms':0}];s[1]['workers'][0]['pending']=[{'id':'1-0','consumer':'a','deliveries':2,'idle_ms':30000}];self.assertTrue(evaluate(w,s,n)['pass'])
 def test_slow_total_task_time(self):
  w,s,n=self.fixture()
  for sample in s[10:65]:sample['workers'][0]['pending']=[{'id':'task-0','consumer':'a','deliveries':1,'idle_ms':1000,'recording_id':'0'}]
  self.assertFalse(evaluate(w,s,n)['pass'])
 def test_host_only_counters_rejected(self):
  w,s,n=self.fixture();n[0]['tcp_scope']='host';self.assertFalse(evaluate(w,s,n)['pass'])
 def test_host_retransmissions_do_not_block_workers(self):
  w,s,n=self.fixture()
  for i,row in enumerate(n):row['host_tcp']={'OutSegs':i*100,'RetransSegs':i*50}
  self.assertTrue(evaluate(w,s,n)['pass'])
 def test_missing_container_rejected(self):
  w,s,n=self.fixture();n[-1]['containers']={};self.assertFalse(evaluate(w,s,n)['pass'])
if __name__=='__main__':unittest.main()
