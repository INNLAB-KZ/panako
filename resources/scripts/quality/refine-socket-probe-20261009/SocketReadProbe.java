import java.sql.*;
import java.util.Properties;

/** Read-only, bounded transport comparison; never consumes or publishes tasks. */
public class SocketReadProbe {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("OLAF_CLICKHOUSE_URL");
        for (int pass = 0; pass < 6; pass++) {
            boolean autotune = (pass % 2) == 1;
            Properties properties = new Properties();
            properties.setProperty("socket_timeout", "30000");
            properties.setProperty("connection_timeout", "5000");
            if (autotune) {
                properties.setProperty("socket_rcvbuf", "0");
                properties.setProperty("socket_sndbuf", "0");
            }
            long start = System.nanoTime();
            long count = 0, checksum = 0;
            try (Connection connection = DriverManager.getConnection(url, properties);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT reinterpretAsInt64(sipHash64(number)), toInt32(number), " +
                         "toInt32(number % 3600) FROM numbers(2000000) " +
                         "SETTINGS max_threads=1, max_memory_usage=134217728, max_execution_time=30")) {
                while (rows.next()) {
                    checksum += rows.getLong(1) + rows.getInt(2) + rows.getInt(3);
                    count++;
                }
            }
            System.out.printf("pass=%d autotune=%s rows=%d checksum=%d elapsed_ms=%d%n",
                    pass, autotune, count, checksum, (System.nanoTime() - start) / 1000000);
            if (count != 2000000) throw new IllegalStateException("Incomplete result");
        }
    }
}
