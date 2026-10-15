import mmap,struct,collections,json,sys,time
p=sys.argv[1];f=open(p,'rb');m=mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ)
o=m.find(b'\0')+1;ids=struct.unpack_from('>I',m,o)[0];o+=12
sizes={2:ids,4:1,5:2,6:4,7:8,8:1,9:2,10:4,11:8}
strings={};classes={};instance_sizes={};counts=collections.Counter();payload=collections.Counter()
def u2(i):return struct.unpack_from('>H',m,i)[0]
def u4(i):return struct.unpack_from('>I',m,i)[0]
def ident(i):return struct.unpack_from('>Q' if ids==8 else '>I',m,i)[0]
roots={0xff:ids,1:2*ids,2:ids+8,3:ids+8,4:ids+4,5:ids,6:ids+4,7:ids,8:ids+8,0x89:ids,0x8a:ids,0x8b:ids,0x8c:ids,0x8d:ids+8,0x8e:ids,0x90:ids}
while o<len(m):
 tag=m[o];length=u4(o+5);s=o+9;end=s+length;o=end
 if tag==1:
  strings[ident(s)]=m[s+ids:end].decode('utf-8','replace')
 elif tag==2:classes[ident(s+4)]=ident(s+8+ids)
 elif tag in (0x0c,0x1c):
  while s<end:
   t=m[s];s+=1
   if t in roots:s+=roots[t]
   elif t==0x20:
    cid=ident(s);s+=ids+4+6*ids;instance_sizes[cid]=u4(s);s+=4
    n=u2(s);s+=2
    for _ in range(n):typ=m[s+2];s+=3+sizes[typ]
    n=u2(s);s+=2
    for _ in range(n):typ=m[s+ids];s+=ids+1+sizes[typ]
    n=u2(s);s+=2+n*(ids+1)
   elif t==0x21:
    cid=ident(s+ids+4);n=u4(s+2*ids+4);counts[cid]+=1;payload[cid]+=n;s+=2*ids+8+n
   elif t==0x22:
    n=u4(s+ids+4);cid=ident(s+ids+8);counts[cid]+=1;payload[cid]+=n*ids;s+=2*ids+8+n*ids
   elif t==0x23:
    n=u4(s+ids+4);typ=m[s+ids+8];cid='primitive_array_'+str(typ);counts[cid]+=1;payload[cid]+=n*sizes[typ];s+=ids+9+n*sizes[typ]
   elif t==0xfe:s+=4+ids
   else:raise RuntimeError('Unknown heap subtag '+hex(t)+' at '+str(s-1))
  assert s==end
rows=[]
for cid,n in counts.most_common(30):
 rows.append({'class':strings.get(classes.get(cid),str(cid)),'objects':n,'serialized_field_bytes':payload[cid],'hprof_instance_size':instance_sizes.get(cid)})
print(json.dumps({'id_bytes':ids,'top_by_count':rows},indent=2))
