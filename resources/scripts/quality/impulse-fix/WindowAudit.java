package be.panako.http;
import be.panako.strategy.olaf.*;
import be.panako.util.*;
import be.tarsos.dsp.io.*;
import java.nio.file.*;
import java.util.*;
public final class WindowAudit {
 public static void main(String[] args) throws Exception {
  Locale.setDefault(Locale.US);
  Config.getInstance();
  for(Key k:Key.values()){String v=System.getenv(k.name());if(v!=null&&!v.isEmpty())Config.set(k,v);}
  PipedAudioStream.setDecoder(new PipeDecoder(Config.get(Key.DECODER_PIPE_ENVIRONMENT),Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG),Config.get(Key.DECODER_PIPE_COMMAND),Config.get(Key.DECODER_PIPE_LOG_FILE),Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE)));
  int step=Config.getInt(Key.MONITOR_STEP_SIZE),overlap=Config.getInt(Key.MONITOR_OVERLAP);
  double duration=MonitorHandler.getAudioDuration(args[0]);
  Path output=Path.of(args[1]);Files.createDirectories(output);
  System.out.println("AUDIT_START duration="+duration+" step="+step+" overlap="+overlap);
  for(int offset=0;offset+step<duration;offset+=step-overlap){
   long start=System.nanoTime();Path chunk=MonitorHandler.extractAudioChunk(args[0],offset,step);
   try{
    var prints=new OlafStrategy().toFingerprints(chunk.toString());
    int max=prints.stream().mapToInt(p->p.t3).max().orElse(0);
    var digest=java.security.MessageDigest.getInstance("SHA-256");
    var clean=java.security.MessageDigest.getInstance("SHA-256");
    var bytes=java.nio.ByteBuffer.allocate(44);int cleanCount=0;
    for(var fp:prints){
     bytes.clear();bytes.putLong(fp.hash());
     bytes.putInt(fp.t1).putInt(fp.f1).putFloat(fp.m1).putInt(fp.t2).putInt(fp.f2).putFloat(fp.m2).putInt(fp.t3).putInt(fp.f3).putFloat(fp.m3);
     digest.update(bytes.array());
     if(offset!=1740 || (!noiseTime(fp.t1)&&!noiseTime(fp.t2)&&!noiseTime(fp.t3))){clean.update(bytes.array());cleanCount++;}
    }
    String sha=hex(digest.digest());
    String cleanSha=hex(clean.digest());
    String line=String.format(Locale.ROOT,"{\"offset\":%d,\"bytes\":%d,\"fingerprints\":%d,\"max_t3\":%d,\"seconds\":%.3f}%n",offset,Files.size(chunk),prints.size(),max,(System.nanoTime()-start)/1e9);
    line=line.trim();line=line.substring(0,line.length()-1)+",\"sha256\":\""+sha+"\",\"clean_sha256\":\""+cleanSha+"\",\"clean_count\":"+cleanCount+"}\n";
    Files.writeString(output.resolve("windows.jsonl"),line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    System.out.print("AUDIT_WINDOW "+line);
    if(prints.size()>100000)Files.copy(chunk,output.resolve("heavy-"+offset+".wav"),StandardCopyOption.REPLACE_EXISTING);
   }finally{Files.deleteIfExists(chunk);}
  }
  Files.writeString(output.resolve("complete.txt"),"complete\n");
 }
 static boolean noiseTime(int t){return t==2921||t==2945||t==2969||t==2993;}
 static String hex(byte[] a){StringBuilder b=new StringBuilder();for(byte x:a)b.append(String.format("%02x",x&255));return b.toString();}

}
