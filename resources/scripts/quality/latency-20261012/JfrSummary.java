import jdk.jfr.consumer.*;
import java.nio.file.Path;
import java.util.*;

/** Summarize selected JFR events without emitting captured environment or URLs. */
public class JfrSummary {
    static void add(Map<String,Long> m,String k,long v){m.merge(k,v,Long::sum);}
    static String method(RecordedFrame f){return f.getMethod().getType().getName()+"."+f.getMethod().getName();}
    static void top(String label,Map<String,Long> m){
        System.out.println(label);
        m.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(18)
            .forEach(e->System.out.println(e.getValue()+"\t"+e.getKey()));
    }
    public static void main(String[] args)throws Exception{
        Map<String,Long> cpu=new HashMap<>(),cpuSite=new HashMap<>(),allocation=new HashMap<>(),allocationSite=new HashMap<>(),sockets=new HashMap<>();
        Map<Long,Long> gcPauses=new HashMap<>();long samples=0,weight=0,reads=0,readNanos=0,maxThrottleNanos=0;
        try(RecordingFile file=new RecordingFile(Path.of(args[0]))){
            while(file.hasMoreEvents()){
                RecordedEvent e=file.readEvent();String type=e.getEventType().getName();
                if(type.equals("jdk.ExecutionSample")){
                    RecordedStackTrace st=e.getStackTrace();if(st!=null&&!st.getFrames().isEmpty()){
                        samples++;add(cpu,method(st.getFrames().get(0)),1);
                        String site=st.getFrames().stream().map(JfrSummary::method).filter(x->x.startsWith("be.panako.")||x.startsWith("com.clickhouse.")).findFirst().orElse("other");
                        add(cpuSite,site,1);
                    }
                }else if(type.equals("jdk.ObjectAllocationSample")){
                    long n=e.getLong("weight");weight+=n;add(allocation,e.getClass("objectClass").getName(),n);
                    RecordedStackTrace st=e.getStackTrace();
                    if(st!=null){
                        String site=st.getFrames().stream().map(JfrSummary::method).filter(x->x.startsWith("be.panako.")||x.startsWith("com.clickhouse.")).findFirst().orElse("other");
                        add(allocationSite,site,n);
                    }
                }else if(type.equals("jdk.GarbageCollection")){
                    gcPauses.put(e.getLong("gcId"),e.getDuration("sumOfPauses").toNanos());
                }else if(type.equals("jdk.SocketRead")){
                    reads++;long n=e.getDuration().toNanos();readNanos+=n;add(sockets,e.getString("address")+":"+e.getInt("port"),n);
                }else if(type.equals("jdk.ContainerCPUThrottling")){
                    maxThrottleNanos=Math.max(maxThrottleNanos,e.getDuration("cpuThrottledTime").toNanos());
                }
            }
        }
        System.out.println("execution_samples="+samples+" allocation_sample_weight_bytes="+weight);
        System.out.println("gc_count="+gcPauses.size()+" gc_pause_seconds="+gcPauses.values().stream().mapToLong(x->x).sum()/1e9);
        System.out.println("recorded_socket_reads="+reads+" socket_read_seconds_overlapping="+readNanos/1e9+" container_throttled_cpu_seconds_max="+maxThrottleNanos/1e9);
        top("CPU_TOP_FRAME_SAMPLES",cpu);top("CPU_SAMPLES_BY_FIRST_APPLICATION_OR_DRIVER_FRAME",cpuSite);top("ESTIMATED_ALLOCATION_BYTES_BY_CLASS",allocation);top("ESTIMATED_ALLOCATION_BYTES_BY_FIRST_APPLICATION_OR_DRIVER_FRAME",allocationSite);top("SOCKET_READ_NANOSECONDS_BY_ENDPOINT",sockets);
    }
}
