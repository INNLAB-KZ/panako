package be.panako.http;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Disposable local ClickHouse only; never connects to production. */
class IndexStatusClickHouseTest {
    @Test
    void actualSqlDistinguishesStaleMetadataOrphanFingerprintsAndUnavailableTables() throws Exception {
        String url = System.getenv("INDEX_STATUS_CLICKHOUSE_URL");
        assumeTrue(url != null && !url.isEmpty(), "INDEX_STATUS_CLICKHOUSE_URL not set");
        assertTrue(url.matches("jdbc:ch://(localhost|127\\.0\\.0\\.1):[0-9]+(\\?.*)?"));
        String database = "panako_index_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String base = url.replaceAll("\\?.*", ""), query = url.substring(base.length());
        try (Connection admin = DriverManager.getConnection(url); Statement ddl = admin.createStatement()) {
            ddl.execute("CREATE DATABASE " + database);
            try (Connection connection = DriverManager.getConnection(base + "/" + database + query);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE olaf_metadata (resource_id Int64,path String,duration Float32,num_fingerprints Int32) ENGINE=ReplacingMergeTree ORDER BY resource_id");
                statement.execute("CREATE TABLE olaf_fingerprints (hash Int64,resource_id Int32,t1 Int32) ENGINE=MergeTree ORDER BY hash");
                statement.execute("INSERT INTO olaf_metadata VALUES (1,'original.m4a',356,2),(2,'stale.m4a',356,2474),(3,'invalid.m4a',0,1)");
                statement.execute("INSERT INTO olaf_fingerprints VALUES (10,1,1),(11,1,2),(12,4,3)");
                assertEquals(IndexStatusHandler.State.INDEXED, IndexStatusHandler.check(connection, 1));
                assertEquals(IndexStatusHandler.State.MISSING_FINGERPRINTS, IndexStatusHandler.check(connection, 2));
                assertEquals(IndexStatusHandler.State.INVALID_METADATA, IndexStatusHandler.check(connection, 3));
                assertEquals(IndexStatusHandler.State.MISSING_METADATA, IndexStatusHandler.check(connection, 4));
                assertEquals(IndexStatusHandler.State.MISSING_METADATA, IndexStatusHandler.check(connection, 5));
                statement.execute("DROP TABLE olaf_fingerprints");
                assertThrows(SQLException.class, () -> IndexStatusHandler.check(connection, 1));
                // Missing metadata is resolved cheaply without attempting a fingerprint scan.
                assertEquals(IndexStatusHandler.State.MISSING_METADATA, IndexStatusHandler.check(connection, 5));
            } finally { ddl.execute("DROP DATABASE " + database); }
        }
    }
}
