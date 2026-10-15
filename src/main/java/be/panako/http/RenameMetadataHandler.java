package be.panako.http;

import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.strategy.panako.storage.PanakoStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.FileUtils;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/store/rename — change the {@code path} column on an existing
 * metadata row for an already-indexed track. Fingerprints are not touched, only
 * the descriptive label changes.
 *
 * <p>Accepts JSON with one of the following shapes:</p>
 * <ul>
 *   <li>{@code {"identifier": 1234567890, "new_path": "RUAGT2342680.m4a"}}</li>
 *   <li>{@code {"old_path": "/tmp/1000067653.mp3", "new_path": "RUAGT2342680.m4a"}}
 *       — the {@code resource_id} is recomputed via
 *       {@link FileUtils#getIdentifier(String)} on {@code old_path}.</li>
 * </ul>
 *
 * <p>Returns 200 {@code {"status":"ok"}} on success, 404
 * {@code {"status":"not_found"}} when no row exists for the resolved
 * {@code resource_id}, 400 on bad input.</p>
 */
public class RenameMetadataHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(RenameMetadataHandler.class.getName());

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}
		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String newPath = extractJsonString(body, "new_path");
			if (newPath == null || newPath.isEmpty()) {
				HttpUtil.sendError(exchange, 400, "Missing 'new_path'");
				return;
			}

			Long identifier = extractJsonLong(body, "identifier");
			String oldPath = extractJsonString(body, "old_path");

			long resourceId;
			if (identifier != null) {
				resourceId = identifier;
			} else if (oldPath != null && !oldPath.isEmpty()) {
				resourceId = FileUtils.getIdentifier(oldPath);
			} else {
				HttpUtil.sendError(exchange, 400, "Must provide either 'identifier' or 'old_path'");
				return;
			}

			boolean updated = updatePath(resourceId, newPath);
			if (!updated) {
				String json = "{\"status\":\"not_found\","
						+ "\"identifier\":" + resourceId + ","
						+ "\"new_path\":\"" + HttpUtil.escapeJson(newPath) + "\"}";
				HttpUtil.sendJson(exchange, 404, json);
				return;
			}

			String json = "{\"status\":\"ok\","
					+ "\"identifier\":" + resourceId + ","
					+ "\"new_path\":\"" + HttpUtil.escapeJson(newPath) + "\"}";
			HttpUtil.sendJson(exchange, 200, json);
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Rename request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * Dispatch to the metadata-update method on the storage backend configured
	 * for the active strategy. Returns {@code false} when ClickHouse is not the
	 * active backend (the LMDB backends store path inside the metadata blob and
	 * cannot be patched in place).
	 */
	static boolean updatePath(long resourceId, String newPath) {
		boolean isOlaf = Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF");
		if (isOlaf) {
			if (!Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				LOG.warning("Rename requested but OLAF_STORAGE != CLICKHOUSE — ignored");
				return false;
			}
			return OlafStorageClickHouse.getInstance().updateMetadataPath(resourceId, newPath);
		} else {
			if (!Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				LOG.warning("Rename requested but PANAKO_STORAGE != CLICKHOUSE — ignored");
				return false;
			}
			return PanakoStorageClickHouse.getInstance().updateMetadataPath(resourceId, newPath);
		}
	}

	private static String extractJsonString(String json, String key) {
		if (json == null) return null;
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx == -1) return null;
		int colon = json.indexOf(':', idx + search.length());
		if (colon == -1) return null;
		int start = json.indexOf('"', colon + 1);
		if (start == -1) return null;
		start++;
		int end = start;
		while (end < json.length()) {
			if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
			end++;
		}
		if (end >= json.length()) return null;
		return json.substring(start, end);
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
