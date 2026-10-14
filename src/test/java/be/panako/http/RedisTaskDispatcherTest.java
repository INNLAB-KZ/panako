package be.panako.http;

import org.junit.jupiter.api.*;
import redis.clients.jedis.*;
import redis.clients.jedis.params.*;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RedisTaskDispatcherTest {
    private static Process redis;
    private static JedisPool pool;
    private static int redisPort;
    private static final String STREAM = "jobs";
    private static final String GROUP = "workers";

    @BeforeAll
    static void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        redisPort = port;
        redis = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", String.valueOf(port),
                "--save", "", "--appendonly", "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        pool = new JedisPool("127.0.0.1", port);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                try (Jedis jedis = pool.getResource()) { if ("PONG".equals(jedis.ping())) break; }
                catch (Exception e) { Thread.sleep(20); }
            }
        });
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (pool != null) pool.close();
        if (redis != null) { redis.destroy(); redis.waitFor(5, TimeUnit.SECONDS); }
    }

    @BeforeEach
    void reset() {
        try (Jedis j = pool.getResource()) {
            j.flushDB();
            j.xgroupCreate(STREAM, GROUP, new StreamEntryID(0, 0), true);
        }
    }

    private StreamEntryID enqueue(Jedis j) {
        return j.xadd(STREAM, StreamEntryID.NEW_ENTRY, Map.of("value", "task"));
    }

    private void deliverToDeadConsumer(Jedis j) {
        j.xreadGroup(GROUP, "dead", XReadGroupParams.xReadGroupParams().count(1),
                Map.of(STREAM, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));
    }

    @Test
    void freshAndReclaimedTasksNeverOverlap() throws Exception {
        try (Jedis j = pool.getResource()) {
            enqueue(j); deliverToDeadConsumer(j); enqueue(j);
        }
        Thread.sleep(180);
        RedisTaskDispatcher dispatcher = new RedisTaskDispatcher(pool, GROUP, "live", List.of(STREAM), 150, 10);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), processed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        RedisTaskDispatcher.Handler handler = (j, stream, entry) -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            int index = processed.incrementAndGet();
            if (index == 1) {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            active.decrementAndGet();
        };
        try {
            Future<?> first = callers.submit(() -> dispatcher.processNext(handler));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> second = callers.submit(() -> dispatcher.processNext(handler));
            Thread.sleep(100);
            assertEquals(1, processed.get());
            release.countDown();
            first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
            assertEquals(2, processed.get()); assertEquals(1, maximum.get());
            try (Jedis j = pool.getResource()) { assertEquals(0, j.xpending(STREAM, GROUP).getTotal()); }
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test
    void heartbeatProtectsTaskLongerThanClaimTimeout() {
        try (Jedis j = pool.getResource()) { enqueue(j); }
        RedisTaskDispatcher dispatcher = new RedisTaskDispatcher(pool, GROUP, "live", List.of(STREAM), 300, 10);
        assertTrue(dispatcher.processNext((j, stream, entry) -> {
            try { Thread.sleep(1000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            try (Jedis rival = pool.getResource()) {
                assertTrue(rival.xautoclaim(STREAM, GROUP, "rival", 300,
                        new StreamEntryID(0, 0), XAutoClaimParams.xAutoClaimParams().count(1)).getValue().isEmpty());
            }
        }));
    }

    @Test
    void fatalFailureRemainsPendingAndCanBeRecovered() throws Exception {
        try (Jedis j = pool.getResource()) { enqueue(j); }
        RedisTaskDispatcher dispatcher = new RedisTaskDispatcher(pool, GROUP, "live", List.of(STREAM), 150, 10);
        assertThrows(OutOfMemoryError.class, () -> dispatcher.processNext((j, s, entry) -> {
            throw new OutOfMemoryError("simulated task failure");
        }));
        Thread.sleep(250);
        try (Jedis rival = pool.getResource()) {
            assertEquals(1, rival.xpending(STREAM, GROUP).getTotal());
            assertEquals(1, rival.xautoclaim(STREAM, GROUP, "rival", 150,
                    new StreamEntryID(0, 0), XAutoClaimParams.xAutoClaimParams().count(1)).getValue().size());
        }
    }

    @Test
    void unsuccessfulHandlerDoesNotAcknowledgeInput() {
        try (Jedis j = pool.getResource()) { enqueue(j); }
        RedisTaskDispatcher dispatcher = new RedisTaskDispatcher(pool, GROUP, "live", List.of(STREAM), 300, 10);
        assertThrows(IllegalStateException.class, () -> dispatcher.processNext((j, s, entry) -> {
            throw new IllegalStateException("result publication failed");
        }));
        try (Jedis j = pool.getResource()) { assertEquals(1, j.xpending(STREAM, GROUP).getTotal()); }
    }

    @Test
    void lostOwnershipIsNeitherStolenBackNorAcknowledged() {
        try (Jedis j = pool.getResource()) { enqueue(j); }
        RedisTaskDispatcher dispatcher = new RedisTaskDispatcher(pool, GROUP, "live", List.of(STREAM), 150, 10);
        dispatcher.processNext((j, s, entry) -> {
            try (Jedis rival = pool.getResource()) {
                rival.xclaim(STREAM, GROUP, "rival", 0, new XClaimParams(), entry.getID());
            }
            try { Thread.sleep(250); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        try (Jedis j = pool.getResource()) {
            assertEquals(1, j.xpending(STREAM, GROUP).getTotal());
            assertTrue(j.xpending(STREAM, GROUP).getConsumerMessageCount().containsKey("rival"));
        }
    }

    @Test
    void heartbeatDoesNotReportOwnSuccessfulAckAsOwnershipLoss() {
        try (Jedis j = pool.getResource()) { enqueue(j); }
        var warnings = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger = java.util.logging.Logger.getLogger(RedisTaskDispatcher.class.getName());
        var logHandler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { warnings.add(record.getMessage()); }
            public void flush() { }
            public void close() { }
        };
        // Hold the ACK caller after Redis has removed the PEL entry. The old
        // implementation renews concurrently and emits a false loss warning.
        try (JedisPool delayedAckPool = new JedisPool("127.0.0.1", redisPort) {
            @Override public Jedis getResource() {
                return new Jedis("127.0.0.1", redisPort) {
                    @Override public Object eval(String script, List<String> keys, List<String> args) {
                        Object result = super.eval(script, keys, args);
                        if (script.contains("'XACK'")) {
                            try { Thread.sleep(250); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                        }
                        return result;
                    }
                };
            }
        }) {
            logger.addHandler(logHandler);
            RedisTaskDispatcher dispatcher = new RedisTaskDispatcher(delayedAckPool, GROUP, "live", List.of(STREAM), 150, 10);
            assertTrue(dispatcher.processNext((j, stream, entry) -> { }));
            assertTrue(warnings.stream().noneMatch(s -> s.contains("ownership lost")), warnings.toString());
            try (Jedis j = pool.getResource()) { assertEquals(0, j.xpending(STREAM, GROUP).getTotal()); }
        } finally { logger.removeHandler(logHandler); }
    }

    @Test
    void multipleStreamsDoNotPrefetchUnprotectedMessages() {
        try (Jedis j = pool.getResource()) {
            enqueue(j);
            j.xgroupCreate("other", GROUP, new StreamEntryID(0, 0), true);
            j.xadd("other", StreamEntryID.NEW_ENTRY, Map.of("value", "other"));
        }
        RedisTaskDispatcher dispatcher = new RedisTaskDispatcher(pool, GROUP, "live", List.of(STREAM, "other"), 300, 10);
        dispatcher.processNext((j, stream, entry) -> assertEquals(0, j.xpending("other", GROUP).getTotal()));
        dispatcher.processNext((j, stream, entry) -> assertEquals("other", stream));
    }
}
