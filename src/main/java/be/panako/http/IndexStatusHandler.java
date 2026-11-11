package be.panako.http;

import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Read-only index presence check. Database failures must never mean "absent". */
public final class IndexStatusHandler implements HttpHandler {
    private static final Logger LOG = Logger.getLogger(IndexStatusHandler.class.getName());
    private static final String LIMITS = " SETTINGS max_threads=2, max_execution_time=15,"
            + " max_memory_usage=268435456, timeout_overflow_mode='throw', read_overflow_mode='throw'";

    enum State { INDEXED, MISSING_METADATA, INVALID_METADATA, MISSING_FINGERPRINTS }

    @FunctionalInterface
    interface Lookup { State check(int identifier) throws SQLException; }

    private final Lookup lookup;
    // Fingerprints are ordered by hash, not resource_id. Do not allow concurrent full scans.
    private final Semaphore capacity = new Semaphore(1);

    public IndexStatusHandler() {
        this.lookup = id -> {
            try (Connection connection = OlafStorageClickHouse.getInstance().openConnection()) {
                return check(connection, id);
            }
        };
    }

    IndexStatusHandler(Lookup lookup) { this.lookup = lookup; }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (!"/api/v1/index/status".equals(exchange.getRequestURI().getPath())) {
            HttpUtil.sendError(exchange, 404, "Not found");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            HttpUtil.sendError(exchange, 405, "Method not allowed");
            return;
        }
        int identifier;
        try {
            String value = HttpUtil.parseQueryParams(exchange).get("identifier");
            if (value == null || !value.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
            identifier = Integer.parseInt(value);
            if (identifier <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            HttpUtil.sendError(exchange, 400, "identifier must be an integer from 1 to 2147483647");
            return;
        }
        if (!"OLAF".equalsIgnoreCase(Config.get(Key.STRATEGY))
                || !"CLICKHOUSE".equalsIgnoreCase(Config.get(Key.OLAF_STORAGE))) {
            HttpUtil.sendError(exchange, 501, "Index status requires OLAF with CLICKHOUSE storage");
            return;
        }
        if (!capacity.tryAcquire()) {
            exchange.getResponseHeaders().set("Retry-After", "5");
            HttpUtil.sendError(exchange, 503, "Index verification busy; retry later");
            return;
        }
        try {
            State state = lookup.check(identifier);
            HttpUtil.sendJson(exchange, 200, "{\"status\":\"ok\",\"identifier\":" + identifier
                    + ",\"indexed\":" + (state == State.INDEXED)
                    + ",\"state\":\"" + state.name().toLowerCase(java.util.Locale.ROOT) + "\"}");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Index verification failed for resource " + identifier, e);
            exchange.getResponseHeaders().set("Retry-After", "5");
            HttpUtil.sendError(exchange, 503, "Index verification unavailable; retry later");
        } finally {
            capacity.release();
        }
    }

    static State check(Connection connection, int identifier) throws SQLException {
        State metadata = metadataState(connection, identifier);
        if (metadata != State.INDEXED) return metadata;
        boolean fingerprints;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM olaf_fingerprints WHERE resource_id=? LIMIT 1" + LIMITS)) {
            statement.setInt(1, identifier);
            try (ResultSet rows = statement.executeQuery()) { fingerprints = rows.next(); }
        }
        // A delete may have removed metadata while the fingerprint scan was running.
        metadata = metadataState(connection, identifier);
        if (metadata != State.INDEXED) return metadata;
        return fingerprints ? State.INDEXED : State.MISSING_FINGERPRINTS;
    }

    private static State metadataState(Connection connection, int identifier) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT path,duration,num_fingerprints FROM olaf_metadata FINAL WHERE resource_id=?" + LIMITS)) {
            statement.setInt(1, identifier);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return State.MISSING_METADATA;
                String path = rows.getString(1);
                double duration = rows.getDouble(2);
                return path != null && !path.isEmpty() && Double.isFinite(duration)
                        && duration > 0 && rows.getInt(3) > 0 ? State.INDEXED : State.INVALID_METADATA;
            }
        }
    }
}
