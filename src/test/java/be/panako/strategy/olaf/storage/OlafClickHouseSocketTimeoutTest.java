package be.panako.strategy.olaf.storage;

import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OlafClickHouseSocketTimeoutTest {
    @Test
    void defaultTimeoutReleasesStalledJdbcBodyDiscardsHitsAndAllowsNextTask() throws Exception {
        String previousUrl = Config.get(Key.OLAF_CLICKHOUSE_URL);
        var executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stalled-clickhouse-test");
            t.setDaemon(true);
            return t;
        });
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        var stall = new AtomicBoolean(true);
        var release = new CountDownLatch(1);
        var partialBodySent = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try {
                String sql = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!sql.contains("SELECT hash")) {
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.getResponseHeaders().add("X-ClickHouse-Format", "RowBinaryWithNamesAndTypes");
                exchange.getResponseHeaders().add("X-ClickHouse-Timezone", "UTC");
                exchange.sendResponseHeaders(200, 0);
                var body = exchange.getResponseBody();
                body.write(rows());
                if (stall.get()) {
                    body.write(1); // Start another Int64, then stop sending mid-row.
                    body.flush();
                    partialBodySent.countDown();
                    release.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            // No socket_timeout override: exercise the actual production default
            // through OlafStorageClickHouse and the real JDBC 0.9.8 driver.
            Config.set(Key.OLAF_CLICKHOUSE_URL, "jdbc:clickhouse:http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/default?compress=false&server_version=26.3.12.3&server_time_zone=UTC");
            var constructor = OlafStorageClickHouse.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            var storage = constructor.newInstance();
            assertTimeoutPreemptively(Duration.ofSeconds(80), () -> {
                storage.addToQueryQueue(123);
                var hits = new HashMap<Long, List<OlafHit>>();
                long start = System.nanoTime();
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> storage.processQueryQueue(hits, 0, Set.of()));
                long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
                assertEquals(0, partialBodySent.getCount(), "Must reach the stalled result body");
                assertTrue(elapsed >= 55000 && elapsed < 75000, "Elapsed ms: " + elapsed);
                boolean socketTimeout = false;
                for (Throwable t = failure; t != null; t = t.getCause()) {
                    if (t instanceof SocketTimeoutException) socketTimeout = true;
                }
                assertTrue(socketTimeout, "Failure must originate from a socket timeout: " + failure);
                assertTrue(hits.isEmpty(), "Partial matches must be discarded");
                stall.set(false);
                release.countDown();
                storage.processQueryQueue(hits, 0, Set.of());
                assertTrue(hits.isEmpty(), "Failed task must leave no queued hashes");
                storage.addToQueryQueue(123);
                storage.processQueryQueue(hits, 0, Set.of());
                assertEquals(1, hits.get(123L).size());
            });
        } finally {
            release.countDown();
            Config.set(Key.OLAF_CLICKHOUSE_URL, previousUrl);
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static byte[] rows() throws java.io.IOException {
        var out = new ByteArrayOutputStream();
        out.write(3);
        for (String s : List.of("hash", "resource_id", "t1", "Int64", "Int32", "Int32")) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(123).putInt(42).putInt(12).array());
        return out.toByteArray();
    }
}
