package be.panako.http;

import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.strategy.panako.storage.PanakoStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/delete/by_id — delete fingerprints and metadata for a given
 * {@code resource_id} without needing the audio file.
 *
 * <p>Accepts JSON: {@code {"identifier": 1979144137}} (or the alias
 * {@code "resource_id"}). Returns the number of rows that existed prior to
 * deletion (fingerprints + metadata combined).</p>
 *
 * <p>Only supported on ClickHouse storage. LMDB backends require the original
 * audio file via {@code /api/v1/delete}.</p>
 */
public class DeleteByIdHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(DeleteByIdHandler.class.getName());

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}
		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			Long resourceId = extractJsonLong(body, "identifier");
			if (resourceId == null) resourceId = extractJsonLong(body, "resource_id");
			if (resourceId == null) {
				HttpUtil.sendError(exchange, 400, "Missing 'identifier' or 'resource_id'");
				return;
			}

			long rowsBefore = deleteById(resourceId);
			if (rowsBefore < 0) {
				HttpUtil.sendError(exchange, 500, "Delete failed (see server log)");
				return;
			}

			String json = "{\"status\":\"ok\","
					+ "\"identifier\":" + resourceId + ","
					+ "\"rows_deleted\":" + rowsBefore + "}";
			HttpUtil.sendJson(exchange, 200, json);
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Delete by_id failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
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
