package be.panako.http;

import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IndexStatusHandlerTest {
    private HttpServer server;
    private ExecutorService executor;
    private String oldStrategy, oldStorage, oldApiKey;
    private volatile IndexStatusHandler.Lookup lookup;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        oldStrategy = Config.get(Key.STRATEGY);
        oldStorage = Config.get(Key.OLAF_STORAGE);
        oldApiKey = Config.get(Key.API_KEY);
        Config.set(Key.STRATEGY, "OLAF");
        Config.set(Key.OLAF_STORAGE, "CLICKHOUSE");
        Config.set(Key.API_KEY, "test-index-key");
        lookup = id -> IndexStatusHandler.State.INDEXED;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/v1/index/status", new ApiKeyFilter(new IndexStatusHandler(id -> {
            calls.incrementAndGet();
            return lookup.check(id);
        })));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
        Config.set(Key.STRATEGY, oldStrategy);
        Config.set(Key.OLAF_STORAGE, oldStorage);
        Config.set(Key.API_KEY, oldApiKey);
    }

    private HttpRequest request(String suffix, boolean authenticated) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/api/v1/index/status" + suffix))
                .timeout(java.time.Duration.ofSeconds(10));
        if (authenticated) builder.header("X-API-Key", "test-index-key");
        return builder.build();
    }

    private HttpResponse<String> get(String suffix) throws Exception {
        return HttpClient.newHttpClient().send(request(suffix, true), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void distinguishesIndexedAndMissingStates() throws Exception {
        for (IndexStatusHandler.State state : IndexStatusHandler.State.values()) {
            lookup = id -> { assertEquals(2027580415, id); return state; };
            HttpResponse<String> response = get("?identifier=2027580415");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"indexed\":" + (state == IndexStatusHandler.State.INDEXED)));
            assertTrue(response.body().contains("\"state\":\"" + state.name().toLowerCase(java.util.Locale.ROOT) + "\""));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        }
    }

    @Test
    void databaseFailureNeverClaimsMissingAndReleasesCapacity() throws Exception {
        lookup = id -> { throw new SQLException("secret connection details"); };
        HttpResponse<String> failed = get("?identifier=1");
        assertEquals(503, failed.statusCode());
        assertFalse(failed.body().contains("indexed"));
        assertFalse(failed.body().contains("secret"));
        lookup = id -> IndexStatusHandler.State.INDEXED;
        assertEquals(200, get("?identifier=1").statusCode());
    }

    @Test
    void invalidInputsAndUnsupportedStorageNeverQuery() throws Exception {
        for (String value : new String[]{"", "abc", "-1", "0", "2147483648", "1%20OR%201=1"}) {
            assertEquals(400, get("?identifier=" + value).statusCode());
        }
        assertEquals(400, get("").statusCode());
        assertEquals(404, get("/extra?identifier=1").statusCode());
        Config.set(Key.OLAF_STORAGE, "MEM");
        assertEquals(501, get("?identifier=1").statusCode());
        assertEquals(0, calls.get());
    }

    @Test
    void requiresApiKey() throws Exception {
        assertEquals(401, HttpClient.newHttpClient().send(request("?identifier=1", false),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, calls.get());
    }

    @Test
    void concurrentCheckReturnsBusyInsteadOfStartingAnotherScan() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        lookup = id -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException("test timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
            return IndexStatusHandler.State.INDEXED;
        };
        var first = HttpClient.newHttpClient().sendAsync(request("?identifier=1", true), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(503, get("?identifier=2").statusCode());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
        assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
    }
}
