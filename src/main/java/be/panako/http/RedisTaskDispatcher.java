package be.panako.http;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serial admission for fresh and reclaimed messages within one Redis worker. */
final class RedisTaskDispatcher {
    private static final Logger LOG = Logger.getLogger(RedisTaskDispatcher.class.getName());
    // Checking ownership and refreshing idle time must be atomic. Never steal a
    // message back from another consumer after a pause or connection outage.
    private static final String RENEW =
            "local p=redis.call('XPENDING',KEYS[1],ARGV[1],ARGV[3],ARGV[3],1) " +
            "if #p==0 or p[1][2]~=ARGV[2] then return 0 end " +
            "local ids=redis.call('XCLAIM',KEYS[1],ARGV[1],ARGV[2],0,ARGV[3],'IDLE',0,'JUSTID') " +
            "return #ids";
    private static final String ACK =
            "local p=redis.call('XPENDING',KEYS[1],ARGV[1],ARGV[3],ARGV[3],1) " +
            "if #p==0 or p[1][2]~=ARGV[2] then return 0 end " +
            "return redis.call('XACK',KEYS[1],ARGV[1],ARGV[3])";

    interface Handler {
        void process(Jedis jedis, String stream, StreamEntry entry);
    }

    private final JedisPool pool;
    private final String group;
    private final String consumer;
    private final List<String> streams;
    private final long claimIdleMs;
    private final long blockMs;
    private final Map<String, StreamEntryID> cursors = new HashMap<>();
    private int streamIndex;
    private final Map<String, Boolean> reclaimFirstByStream = new HashMap<>();

    RedisTaskDispatcher(JedisPool pool, String group, String consumer,
                        List<String> streams, long claimIdleMs, long blockMs) {
        if (claimIdleMs <= 0 || blockMs <= 0 || streams.isEmpty()) {
            throw new IllegalArgumentException("Positive idle/block times and at least one stream are required");
        }
        this.pool = pool;
        this.group = group;
        this.consumer = consumer;
        this.streams = List.copyOf(streams);
        this.claimIdleMs = claimIdleMs;
        this.blockMs = blockMs;
    }

    /** Do not prefetch: waiting tasks would age in the PEL without a heartbeat. */
    synchronized boolean processNext(Handler handler) {
        String stream = streams.get(streamIndex++ % streams.size());
        streamIndex %= streams.size();
        boolean reclaimFirst = reclaimFirstByStream.getOrDefault(stream, true);
        reclaimFirstByStream.put(stream, !reclaimFirst);
        try (Jedis jedis = pool.getResource()) {
            StreamEntry entry = reclaimFirst ? reclaim(jedis, stream) : null;
            if (entry == null) {
                List<Map.Entry<String, List<StreamEntry>>> response = jedis.xreadGroup(
                        group, consumer, XReadGroupParams.xReadGroupParams().count(1).block((int) blockMs),
                        Map.of(stream, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));
                if (response != null && !response.isEmpty() && !response.get(0).getValue().isEmpty()) {
                    entry = response.get(0).getValue().get(0);
                }
            }
            if (entry == null && !reclaimFirst) entry = reclaim(jedis, stream);
            if (entry == null) return false;

            final StreamEntry active = entry;
            // Refresh immediately before starting and keep the lease alive even
            // when audio processing takes longer than the reclaim idle threshold.
            if (!renew(jedis, stream, active.getID())) return false;
            ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "panako-redis-heartbeat");
                t.setDaemon(true);
                return t;
            });
            long interval = Math.max(1, Math.min(30_000, claimIdleMs / 3));
            // Serialize renewal with final ACK. A renewal already scheduled at
            // completion must not report our own successful ACK as lease loss.
            AtomicBoolean finished = new AtomicBoolean();
            heartbeat.scheduleWithFixedDelay(() -> {
                synchronized (finished) {
                    if (finished.get()) return;
                    try (Jedis leaseConnection = pool.getResource()) {
                        if (!renew(leaseConnection, stream, active.getID())) {
                            LOG.warning("Redis task ownership lost for " + stream + ":" + active.getID());
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Redis task heartbeat failed", e);
                    }
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
            try {
                handler.process(jedis, stream, active);
                // Errors (including OOM) must leave the message pending.
                synchronized (finished) {
                    finished.set(true);
                    acknowledge(jedis, stream, active.getID());
                }
            } finally {
                synchronized (finished) {
                    finished.set(true);
                }
                heartbeat.shutdownNow();
            }
            return true;
        }
    }

    private StreamEntry reclaim(Jedis jedis, String stream) {
        Map.Entry<StreamEntryID, List<StreamEntry>> result = jedis.xautoclaim(
                stream, group, consumer, claimIdleMs,
                cursors.getOrDefault(stream, new StreamEntryID(0, 0)),
                XAutoClaimParams.xAutoClaimParams().count(1));
        if (result == null) return null;
        cursors.put(stream, result.getKey());
        return result.getValue().isEmpty() ? null : result.getValue().get(0);
    }

    private boolean renew(Jedis jedis, String stream, StreamEntryID id) {
        return Long.valueOf(1).equals(jedis.eval(RENEW, List.of(stream),
                List.of(group, consumer, id.toString())));
    }

    private void acknowledge(Jedis jedis, String stream, StreamEntryID id) {
        if (!Long.valueOf(1).equals(jedis.eval(ACK, List.of(stream),
                List.of(group, consumer, id.toString())))) {
            LOG.warning("Redis task not acknowledged: ownership lost for " + stream + ":" + id);
        }
    }
}
