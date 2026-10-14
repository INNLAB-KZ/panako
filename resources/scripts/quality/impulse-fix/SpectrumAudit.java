package be.panako.http;
import be.panako.strategy.olaf.*;
import be.panako.util.*;
import be.tarsos.dsp.io.*;
import be.tarsos.dsp.io.jvm.*;
import java.util.*;
public final class SpectrumAudit {
 public static void main(String[] args) throws Exception {
  Config.getInstance();
  for(Key k:Key.values()){String v=System.getenv(k.name());if(v!=null&&!v.isEmpty())Config.set(k,v);}
  PipedAudioStream.setDecoder(new PipeDecoder(Config.get(Key.DECODER_PIPE_ENVIRONMENT),Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG),Config.get(Key.DECODER_PIPE_COMMAND),Config.get(Key.DECODER_PIPE_LOG_FILE),Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE)));
  int sr=Config.getInt(Key.OLAF_SAMPLE_RATE), size=Config.getInt(Key.OLAF_SIZE),step=Config.getInt(Key.OLAF_STEP_SIZE);
  var d=AudioDispatcherFactory.fromPipe(args[0],sr,size,size-step,0);
  var proc=new OlafEventPointProcessor(size){@Override public void processingFinished(){}};
  d.addAudioProcessor(proc);d.run();
  var frames=new TreeMap<Integer,List<OlafEventPoint>>();
  var mags=new HashMap<Float,Integer>();
  for(var e:proc.getEventPoints()){frames.computeIfAbsent(e.t,k->new ArrayList<>()).add(e);mags.merge(e.m,1,Integer::sum);}
  System.out.println("EVENTS "+proc.getEventPoints().size()+" frames="+frames.size()+" sample_rate="+sr+" step="+step);
  frames.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue().size(),a.getValue().size())).limit(12).forEach(e->{
   var v=e.getValue();System.out.println("FRAME t="+e.getKey()+" seconds="+(e.getKey()*step/(double)sr)+" events="+v.size()+" min_f="+v.stream().mapToInt(x->x.f).min().orElse(0)+" max_f="+v.stream().mapToInt(x->x.f).max().orElse(0)+" magnitude_min="+v.stream().mapToDouble(x->x.m).min().orElse(0)+" magnitude_max="+v.stream().mapToDouble(x->x.m).max().orElse(0));});
  mags.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue(),a.getValue())).limit(10).forEach(e->System.out.println("MAG "+e.getKey()+" count="+e.getValue()));
 }
}
