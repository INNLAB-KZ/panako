package be.panako.http;

import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.strategy.panako.storage.PanakoStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/delete/by_id and /api/v1/delete/by_ids — delete fingerprints and
 * metadata by {@code resource_id} without needing the audio file.
 *
 * <p>{@code by_id} accepts {@code {"identifier": 1979144137}} (or the alias
 * {@code "resource_id"}). {@code by_ids} accepts
 * {@code {"identifiers": [1075536417, 1087869885]}}, at most
 * {@value #MAX_IDS} ids, and returns the deleted fingerprint count per id.</p>
 *
 * <p>On OLAF the metadata is deleted at once and the fingerprint delete is
 * queued; see {@link OlafStorageClickHouse#deleteByResourceIds}. Only supported
 * on ClickHouse storage. LMDB backends require the original audio file via
 * {@code /api/v1/delete}.</p>
 */
public class DeleteByIdHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(DeleteByIdHandler.class.getName());

	static final int MAX_IDS = 100;

	private final boolean batch;

	/** Handler for {@code by_id}. */
	public DeleteByIdHandler() {
		this(false);
	}

	/** Handler for {@code by_ids} when {@code batch} is true, else for {@code by_id}. */
	public DeleteByIdHandler(boolean batch) {
		this.batch = batch;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}
		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (batch) {
				deleteMany(exchange, body);
				return;
			}
			Long resourceId = extractJsonLong(body, "identifier");
			if (resourceId == null) resourceId = extractJsonLong(body, "resource_id");
			if (resourceId == null) {
				HttpUtil.sendError(exchange, 400, "Missing 'identifier' or 'resource_id'");
				return;
			}

			long rowsBefore;
			if (isOlafClickHouse()) {
				rowsBefore = OlafStorageClickHouse.getInstance()
						.deleteByResourceIds(Collections.singletonList(resourceId)).get(resourceId);
			} else {
				rowsBefore = deleteById(resourceId);
				if (rowsBefore < 0) {
					HttpUtil.sendError(exchange, 500, "Delete failed (see server log)");
					return;
				}
			}

			String json = "{\"status\":\"ok\","
					+ "\"identifier\":" + resourceId + ","
					+ "\"rows_deleted\":" + rowsBefore + "}";
			HttpUtil.sendJson(exchange, 200, json);
		} catch (IllegalArgumentException e) {
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Delete by id failed", e);
			HttpUtil.sendError(exchange, 500, "Delete failed: " + e.getMessage());
		}
	}

	private void deleteMany(HttpExchange exchange, String body) throws IOException {
		List<Long> ids = DuplicatesHandler.jsonLongArray(body, "identifiers");
		if (ids == null) throw new IllegalArgumentException("Missing 'identifiers' array");
		if (ids.isEmpty()) throw new IllegalArgumentException("'identifiers' is empty");
		if (ids.size() > MAX_IDS) throw new IllegalArgumentException("At most " + MAX_IDS + " identifiers per request");
		if (!isOlafClickHouse()) {
			HttpUtil.sendError(exchange, 400, "delete/by_ids requires STRATEGY=OLAF and OLAF_STORAGE=CLICKHOUSE");
			return;
		}
		long started = System.nanoTime();
		Map<Long, Long> counts = OlafStorageClickHouse.getInstance().deleteByResourceIds(ids);
		LOG.info(String.format("Deleted %d resources by id in %d ms: %s", counts.size(),
				(System.nanoTime() - started) / 1_000_000, counts.keySet()));
		StringBuilder json = new StringBuilder("{\"status\":\"ok\",\"rows_deleted\":{");
		boolean first = true;
		for (Map.Entry<Long, Long> entry : counts.entrySet()) {
			if (!first) json.append(',');
			first = false;
			json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
		}
		HttpUtil.sendJson(exchange, 200, json.append("}}").toString());
	}

	private static boolean isOlafClickHouse() {
		return Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF")
				&& Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE");
	}

	/**
	 * Dispatch to the delete method on the storage backend configured for the
	 * active strategy. Returns {@code -1} when ClickHouse is not the active
	 * backend (LMDB backends are not supported here).
	 */
	static long deleteById(long resourceId) {
		boolean isOlaf = Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF");
		if (isOlaf) {
			if (!Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				LOG.warning("Delete by_id requested but OLAF_STORAGE != CLICKHOUSE — ignored");
				return -1;
			}
			return OlafStorageClickHouse.getInstance().deleteByResourceId(resourceId);
		} else {
			if (!Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				LOG.warning("Delete by_id requested but PANAKO_STORAGE != CLICKHOUSE — ignored");
				return -1;
			}
			return PanakoStorageClickHouse.getInstance().deleteByResourceId(resourceId);
		}
	}

	private static Long extractJsonLong(String json, String key) {
		if (json == null) return null;
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx == -1) return null;
		int colon = json.indexOf(':', idx + search.length());
		if (colon == -1) return null;
		StringBuilder num = new StringBuilder();
		boolean started = false;
		for (int i = colon + 1; i < json.length(); i++) {
			char c = json.charAt(i);
			if (!started && (c == ' ' || c == '\t' || c == '\n' || c == '\r')) continue;
			if (c == '-' || (c >= '0' && c <= '9')) {
				num.append(c);
				started = true;
			} else if (started) {
				break;
			} else {
				return null;
			}
		}
		if (num.length() == 0) return null;
		try {
			return Long.parseLong(num.toString());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
