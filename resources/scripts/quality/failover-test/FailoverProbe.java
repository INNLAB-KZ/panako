import java.sql.*;
import java.nio.file.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Bounded read-only probe using the deployed worker JDBC driver. */
public class FailoverProbe {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("OLAF_CLICKHOUSE_URL");
        String sql=Files.readString(Path.of("/test/sample.sql"));
        for(int i=0;i<180 && !Files.exists(Path.of("/test/stop"));i++) {
            long began=System.nanoTime();
            try(Connection c=DriverManager.getConnection(url); Statement st=c.createStatement()) {
                st.setQueryTimeout(8);
                String host="";
                MessageDigest hash=MessageDigest.getInstance("SHA-256");int rows=0;
                try(ResultSet rs=st.executeQuery(sql.replace("SELECT hash,", "SELECT hostName(),hash,"))) {
                    while(rs.next()) {
                        host=rs.getString(1);
                        String row=rs.getLong(2)+","+rs.getInt(3)+","+rs.getInt(4)+"\n";
                        hash.update(row.getBytes(StandardCharsets.UTF_8));rows++;
                    }
                }
                System.out.printf("{\"at_ms\":%d,\"ok\":true,\"host\":\"%s\",\"rows\":%d,\"digest\":\"%s\",\"elapsed_ms\":%d}%n",System.currentTimeMillis(),host,rows,HexFormat.of().formatHex(hash.digest()),(System.nanoTime()-began)/1000000);
            } catch(Exception ex) {
                System.out.printf("{\"at_ms\":%d,\"ok\":false,\"error_type\":\"%s\",\"elapsed_ms\":%d}%n",System.currentTimeMillis(),ex.getClass().getSimpleName(),(System.nanoTime()-began)/1000000);
            }
            Thread.sleep(400);
        }
    }
}
