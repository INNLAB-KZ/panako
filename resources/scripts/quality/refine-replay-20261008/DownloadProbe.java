package be.panako.http;
import java.nio.file.*;
import be.panako.util.*;
public class DownloadProbe {
 public static void main(String[] a) throws Exception {
  Config.getInstance();
  for(Key k:Key.values()){String v=System.getenv(k.name());if(v!=null&&!v.isEmpty())Config.set(k,v);}
  AudioDownloadRouter.download(Files.readString(Path.of(a[0])).trim(),Path.of(a[1]),200_000_000L);
  System.out.println("DOWNLOAD_OK bytes="+Files.size(Path.of(a[1])));
 }
}
