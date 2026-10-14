package be.panako.strategy.olaf.storage;

import be.panako.util.Config;
import be.panako.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OlafClickHouseQueryFailureTest {
    private final FaultDriver driver = new FaultDriver();
    private String previousUrl;
    private OlafStorageClickHouse storage;

    @BeforeEach
    void setup() throws Exception {
        previousUrl = Config.get(Key.OLAF_CLICKHOUSE_URL);
        DriverManager.registerDriver(driver);
        Config.set(Key.OLAF_CLICKHOUSE_URL, "jdbc:olaf-fault-test:local");
        var constructor = OlafStorageClickHouse.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        storage = constructor.newInstance();
        driver.armed = true;
    }

    @AfterEach
    void cleanup() throws Exception {
        Config.set(Key.OLAF_CLICKHOUSE_URL, previousUrl);
        DriverManager.deregisterDriver(driver);
    }

    @Test
    void connectionFailureInvalidatesQueryAndNextTaskStartsClean() {
        verifyFailure(Fault.CONNECT, 1);
    }

    @Test
    void secondBatchFailureDiscardsAlreadyReturnedHits() {
        verifyFailure(Fault.SECOND_BATCH, 1001);
    }

    @Test
    void brokenResultStreamDiscardsAlreadyReturnedHits() {
        verifyFailure(Fault.RESULT_STREAM, 1);
    }

    @Test
    void queryPreservesRowsLastOriginalMappingAndAvoidFilterAcrossBatches() {
        driver.allRows = true;
        Map<Long, Long> expected = new HashMap<>();
        for (long hash = -300; hash < 2500; hash += 2) {
            storage.addToQueryQueue(hash);
            for (int delta = -2; delta <= 2; delta++) expected.put(hash + delta, hash);
        }
        Map<Long, List<OlafHit>> hits = new HashMap<>();
        storage.processQueryQueue(hits, 2, Set.of());
        Map<Long, Long> actual = new HashMap<>();
        hits.forEach((original, rows) -> rows.forEach(hit -> {
            assertEquals(original.longValue(), hit.originalHash);
            assertEquals(42, hit.resourceID);
            assertEquals(12, hit.t);
            assertNull(actual.put(hit.matchedNearHash, original), "Each DB row must survive exactly once");
        }));
        assertEquals(expected, actual);
        assertEquals(3, driver.queries);
        storage.addToQueryQueue(0);
        hits.clear();
        storage.processQueryQueue(hits, 2, Set.of(42));
        assertTrue(hits.isEmpty());
    }

    @Test
    void cancellationDiscardsPartialHitsAndClearsQueueWithoutClearingInterrupt() {
        storage.addToQueryQueue(1);
        Map<Long, List<OlafHit>> hits = new HashMap<>();
        hits.put(77L, new ArrayList<>(List.of(new OlafHit(77, 77, 1, 1))));
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> storage.processQueryQueue(hits, 0, Set.of()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(hits.isEmpty());
            assertEquals(0, driver.queries);
        } finally {
            Thread.interrupted();
        }
        storage.addToQueryQueue(9001);
        storage.processQueryQueue(hits, 0, Set.of());
        assertEquals(Set.of(9001L), hits.keySet());
    }

    @Test
    void cancellationBetweenBatchesStopsFurtherSqlAndDiscardsReturnedRows() {
        driver.interruptAfterRow = true;
        for (long hash = 0; hash < 1001; hash++) storage.addToQueryQueue(hash);
        Map<Long, List<OlafHit>> hits = new HashMap<>();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> storage.processQueryQueue(hits, 0, Set.of()));
            assertTrue(driver.returnedRow);
            assertEquals(1, driver.queries);
            assertTrue(hits.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private void verifyFailure(Fault fault, int hashes) {
        driver.fault = fault;
        for (int i = 0; i < hashes; i++) storage.addToQueryQueue(i + 1L);
        Map<Long, List<OlafHit>> hits = new HashMap<>();
        var failure = assertThrows(IllegalStateException.class,
                () -> storage.processQueryQueue(hits, 0, Set.of()));
        assertSame(driver.failure, failure.getCause());
        assertTrue(hits.isEmpty(), "Partial database results must not survive failure");
        if (fault != Fault.CONNECT) assertTrue(driver.returnedRow, "Fault must occur after a row was returned");
        if (fault == Fault.SECOND_BATCH) assertEquals(2, driver.queries);
        driver.fault = Fault.NONE;
        storage.addToQueryQueue(9001L);
        storage.processQueryQueue(hits, 0, Set.of());
        assertEquals(Set.of(9001L), hits.keySet(), "Failed task hashes must not leak into next task");
        assertEquals(1, hits.get(9001L).size());
    }

    enum Fault { NONE, CONNECT, SECOND_BATCH, RESULT_STREAM }

    private static class FaultDriver implements Driver {
        final SQLException failure = new SQLException("simulated database connection loss", "08006");
        Fault fault = Fault.NONE;
        boolean armed, returnedRow, allRows, interruptAfterRow;
        int queries;

        @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:olaf-fault-test:"); }
        @Override public Connection connect(String url, Properties properties) throws SQLException {
            if (!acceptsURL(url)) return null;
            if (armed && fault == Fault.CONNECT) throw failure;
            return proxy(Connection.class, (method, args) -> {
                if (method.equals("createStatement")) return statement();
                if (method.equals("close")) return null;
                throw new UnsupportedOperationException(method);
            });
        }
        private Statement statement() {
            return proxy(Statement.class, (method, args) -> {
                if (method.equals("execute")) return true; // Schema setup, no external database.
                if (method.equals("close")) return null;
                if (method.equals("executeQuery")) {
                    if (++queries == 2 && fault == Fault.SECOND_BATCH) throw failure;
                    String sql = (String) args[0];
                    String firstHash = sql.substring(sql.indexOf("IN (") + 4).split("[,)]")[0];
                    if (allRows) {
                        String[] hashes = sql.substring(sql.indexOf("IN (") + 4, sql.lastIndexOf(')')).split(",");
                        int[] row = {-1};
                        return proxy(ResultSet.class, (name, values) -> {
                            if (name.equals("close")) return null;
                            if (name.equals("next")) return ++row[0] < hashes.length;
                            if (name.equals("getLong")) return Long.parseLong(hashes[row[0]]);
                            if (name.equals("getInt")) return "resource_id".equals(values[0]) ? 42 : 12;
                            throw new UnsupportedOperationException(name);
                        });
                    }
                    return result(Long.parseLong(firstHash));
                }
                throw new UnsupportedOperationException(method);
            });
        }
        private ResultSet result(long hash) {
            int[] position = {0};
            return proxy(ResultSet.class, (method, args) -> {
                if (method.equals("close")) return null;
                if (method.equals("next")) {
                    if (position[0]++ == 0) { returnedRow = true; return true; }
                    if (interruptAfterRow) Thread.currentThread().interrupt();
                    if (fault == Fault.RESULT_STREAM) throw failure;
                    return false;
                }
                if (method.equals("getLong")) return hash;
                if (method.equals("getInt")) return "resource_id".equals(args[0]) ? 42 : 12;
                throw new UnsupportedOperationException(method);
            });
        }
        @Override public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    @FunctionalInterface interface Call { Object invoke(String method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> call.invoke(method.getName(), args)));
    }
}
