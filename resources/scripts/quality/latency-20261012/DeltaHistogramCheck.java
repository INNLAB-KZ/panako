package be.panako.strategy.olaf;

import java.lang.reflect.Method;
import java.util.*;

/** Differential check against the deployed histogram, including collision/tie cases. */
public final class DeltaHistogramCheck {
    private static int baseline(List<OlafMatch> hits) {
        Map<Integer,Integer> counts=new HashMap<>();
        for(OlafMatch hit:hits){
            int delta=hit.deltaT();
            if(!counts.containsKey(delta))counts.put(delta,0);
            counts.put(delta,counts.get(delta)+1);
        }
        int best=0,max=0;
        for(Map.Entry<Integer,Integer> e:counts.entrySet())if(e.getValue()>max){max=e.getValue();best=e.getKey();}
        return best;
    }
    public static void main(String[] args)throws Exception{
        Method method=OlafStrategy.class.getDeclaredMethod("mostCommonDeltaTforHitList",List.class);
        method.setAccessible(true);OlafStrategy strategy=new OlafStrategy();Random random=new Random(20261012);
        for(int test=0;test<12000;test++){
            List<OlafMatch> hits=new ArrayList<>();int length=test%501;
            for(int n=0;n<length;n++){
                int delta;
                switch(test%6){
                    case 0:delta=random.nextInt(9)-4;break;
                    case 1:delta=(random.nextInt(100)+128)*65537;break; // identical spread low bits
                    case 2:delta=random.nextInt();break;
                    case 3:delta=n%2==0?Integer.MIN_VALUE:Integer.MAX_VALUE;break;
                    case 4:delta=1000;break; // counts outside Integer cache
                    default:delta=random.nextInt(40000)-20000;
                }
                OlafMatch hit=new OlafMatch();hit.matchTime=delta;hit.queryTime=0;hits.add(hit);
            }
            int expected=baseline(hits),actual=(Integer)method.invoke(strategy,hits);
            if(expected!=actual)throw new AssertionError("case="+test+" expected="+expected+" actual="+actual);
        }
        System.out.println("PASS: 12000 differential histogram cases, including ties, tree bins, negatives and overflow boundaries");
    }
}
