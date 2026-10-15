package be.panako.http;

import be.panako.strategy.olaf.storage.*;
import be.panako.strategy.panako.storage.*;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/store/fingerprints — stores pre-computed fingerprints directly (no audio file needed).
 *
 * <p>Accepts JSON:</p>
 * <pre>{@code
 * {
 *   "filename": "track.mp3",
 *   "identifier": 12345,
 *   "duration": 180.5,
 *   "title": "Artist:Track",
 *   "audio_url": "https://example.com/track.mp3",
 *   "fingerprints": [
 *     {"hash": 123456789, "t1": 10, "f1": 200},
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>The {@code identifier} field is optional. If omitted, it is derived from the filename.</p>
 * <p>The {@code title} and {@code audio_url} fields are optional descriptive metadata used by
 * deployments that index tracks without an ISRC; they are persisted only when the storage
 * backend is ClickHouse.</p>
 * <p>The {@code f1} field is only used by the PANAKO strategy; OLAF ignores it.</p>
 *
 * <p>Supports both OLAF and PANAKO strategies based on server configuration.</p>
 */
public class StoreFingerprintsHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(StoreFingerprintsHandler.class.getName());

	private final ReentrantLock writeLock;
	private final boolean isOlaf;

	public StoreFingerprintsHandler(ReentrantLock writeLock) {
		this.writeLock = writeLock;
		this.isOlaf = Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF");
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}

		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

			String filename = extractJsonString(body, "filename");
			if (filename == null || filename.isEmpty()) {
				HttpUtil.sendError(exchange, 400, "Missing 'filename' in JSON body");
				return;
			}

			int identifier = extractJsonInt(body, "identifier", -1);
			if (identifier == -1) {
				identifier = be.panako.util.FileUtils.getIdentifier(filename);
			}

			double duration = extractJsonDouble(body, "duration", -1);
			if (duration < 0) {
				HttpUtil.sendError(exchange, 400, "Missing or invalid 'duration' in JSON body");
				return;
			}

			String title = extractJsonString(body, "title");
			String audioUrl = extractJsonString(body, "audio_url");

			int arrayStart = body.indexOf("\"fingerprints\"");
			if (arrayStart == -1) {
				HttpUtil.sendError(exchange, 400, "Missing 'fingerprints' in JSON body");
				return;
			}
			int bracketStart = body.indexOf('[', arrayStart);
			int bracketEnd = findMatchingBracket(body, bracketStart);
			if (bracketStart == -1 || bracketEnd == -1) {
				HttpUtil.sendError(exchange, 400, "Invalid 'fingerprints' array");
				return;
			}

			String arrayContent = body.substring(bracketStart + 1, bracketEnd);

			long startTime = System.currentTimeMillis();
			int count;

			writeLock.lock();
			try {
				if (isOlaf) {
					count = storeOlaf(arrayContent, identifier, filename, (float) duration, title, audioUrl);
				} else {
					count = storePanako(arrayContent, identifier, filename, (float) duration, title, audioUrl);
				}
				if (count == -1) {
					// already_exists
					writeLock.unlock();
					sendAlreadyExists(exchange, identifier, filename);
					return;
				}
			} finally {
				if (writeLock.isHeldByCurrentThread()) {
					writeLock.unlock();
				}
			}

			long processingTimeMs = System.currentTimeMillis() - startTime;
			String isrc = HttpUtil.extractIsrc(filename);

			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"status\":\"ok\",");
			json.append("\"identifier\":").append(identifier).append(",");
			json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc != null ? isrc : "")).append("\",");
			json.append("\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\",");
			json.append("\"title\":").append(jsonNullableString(title)).append(",");
			json.append("\"audio_url\":").append(jsonNullableString(audioUrl)).append(",");
			json.append("\"duration_seconds\":").append(String.format("%.1f", duration)).append(",");
			json.append("\"fingerprints_count\":").append(count).append(",");
			json.append("\"processing_time_ms\":").append(processingTimeMs);
			json.append("}");

			HttpUtil.sendJson(exchange, 200, json.toString());

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Store fingerprints request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	private int storeOlaf(String arrayContent, int identifier, String filename, float duration,
						  String title, String audioUrl) {
		OlafStorage db = getOlafStorage();
		OlafResourceMetadata existing = db.getMetadata(identifier);
		if (existing != null) {
			if (existing.duration > 0 && existing.numFingerprints > 0) {
				return -1; // complete data — true duplicate
			}
			// Incomplete data from interrupted store — delete and re-store
			LOG.warning("Incomplete OLAF data for " + filename + " — re-storing");
			db.deleteMetadata(identifier);
		}

		int count = 0;
		int pos = 0;
		while (pos < arrayContent.length()) {
			int objStart = arrayContent.indexOf('{', pos);
			if (objStart == -1) break;
			int objEnd = arrayContent.indexOf('}', objStart);
			if (objEnd == -1) break;

			String obj = arrayContent.substring(objStart, objEnd + 1);
			long hash = extractJsonLong(obj, "hash", -1);
			int t1 = extractJsonInt(obj, "t1", -1);

			if (hash != -1 && t1 != -1) {
				db.addToStoreQueue(hash, identifier, t1);
				count++;
			}
			pos = objEnd + 1;
		}

		db.processStoreQueue();
		storeOlafMetadataWithExtras(db, identifier, filename, duration, count, title, audioUrl);
		return count;
	}

	private int storePanako(String arrayContent, int identifier, String filename, float duration,
							String title, String audioUrl) {
		PanakoStorage db = getPanakoStorage();
		PanakoResourceMetadata existing = db.getMetadata(identifier);
		if (existing != null) {
			if (existing.duration > 0 && existing.numFingerprints > 0) {
				return -1; // complete data — true duplicate
			}
			LOG.warning("Incomplete PANAKO data for " + filename + " — re-storing");
			db.deleteMetadata(identifier);
		}

		int count = 0;
		int pos = 0;
		while (pos < arrayContent.length()) {
			int objStart = arrayContent.indexOf('{', pos);
			if (objStart == -1) break;
			int objEnd = arrayContent.indexOf('}', objStart);
			if (objEnd == -1) break;

			String obj = arrayContent.substring(objStart, objEnd + 1);
			long hash = extractJsonLong(obj, "hash", -1);
			int t1 = extractJsonInt(obj, "t1", -1);
			int f1 = extractJsonInt(obj, "f1", 0);

			if (hash != -1 && t1 != -1) {
				db.addToStoreQueue(hash, identifier, t1, f1);
				count++;
			}
			pos = objEnd + 1;
		}

		db.processStoreQueue();
		storePanakoMetadataWithExtras(db, identifier, filename, duration, count, title, audioUrl);
		return count;
	}

	private void sendAlreadyExists(HttpExchange exchange, int identifier, String filename) throws IOException {
		String isrc = HttpUtil.extractIsrc(filename);
		double dur = 0;
		int fpCount = 0;
		String title = null;
		String audioUrl = null;
		if (isOlaf) {
			if (Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				OlafResourceMetadataExt ext = OlafStorageClickHouse.getInstance().getMetadataExt(identifier);
				if (ext != null) {
					dur = ext.base.duration;
					fpCount = ext.base.numFingerprints;
					title = ext.title;
					audioUrl = ext.audioUrl;
				}
			} else {
				OlafResourceMetadata meta = getOlafStorage().getMetadata(identifier);
				if (meta != null) { dur = meta.duration; fpCount = meta.numFingerprints; }
			}
		} else {
			if (Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				PanakoResourceMetadataExt ext = PanakoStorageClickHouse.getInstance().getMetadataExt(identifier);
				if (ext != null) {
					dur = ext.base.duration;
					fpCount = ext.base.numFingerprints;
					title = ext.title;
					audioUrl = ext.audioUrl;
				}
			} else {
				PanakoResourceMetadata meta = getPanakoStorage().getMetadata(identifier);
				if (meta != null) { dur = meta.duration; fpCount = meta.numFingerprints; }
			}
		}
		StringBuilder json = new StringBuilder();
		json.append("{");
		json.append("\"status\":\"already_exists\",");
		json.append("\"identifier\":").append(identifier).append(",");
		json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc != null ? isrc : "")).append("\",");
		json.append("\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\",");
		json.append("\"title\":").append(jsonNullableString(title)).append(",");
		json.append("\"audio_url\":").append(jsonNullableString(audioUrl)).append(",");
		json.append("\"duration_seconds\":").append(String.format("%.1f", (double) dur)).append(",");
		json.append("\"fingerprints_count\":").append(fpCount);
		json.append("}");
		HttpUtil.sendJson(exchange, 200, json.toString());
	}

	/**
	 * Persist OLAF metadata, additionally writing {@code title} and {@code audio_url}
	 * to the ClickHouse-backed extension table when those extras are present and the
	 * configured storage backend is ClickHouse. Non-ClickHouse backends (LMDB, file,
	 * in-memory) silently drop the extras since their schema has no place for them.
	 */
	static void storeOlafMetadataWithExtras(OlafStorage db, long resourceID, String resourcePath,
											float duration, int count, String title, String audioUrl) {
		boolean hasExtras = title != null || audioUrl != null;
		boolean isClickHouse = Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE");
		if (hasExtras && isClickHouse) {
			OlafStorageClickHouse.getInstance()
					.storeMetadataExt(resourceID, resourcePath, duration, count, title, audioUrl);
			// Also write through the configured (possibly caching) storage so any file-backed
			// cache layer above ClickHouse stays in sync; ReplacingMergeTree dedupes the
			// duplicate row in ClickHouse on the next background merge.
			if (db != OlafStorageClickHouse.getInstance()) {
				db.storeMetadata(resourceID, resourcePath, duration, count);
			}
		} else {
			db.storeMetadata(resourceID, resourcePath, duration, count);
		}
	}

	/**
	 * PANAKO counterpart of {@link #storeOlafMetadataWithExtras}.
	 */
	static void storePanakoMetadataWithExtras(PanakoStorage db, long resourceID, String resourcePath,
											  float duration, int count, String title, String audioUrl) {
		boolean hasExtras = title != null || audioUrl != null;
		boolean isClickHouse = Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("CLICKHOUSE");
		if (hasExtras && isClickHouse) {
			PanakoStorageClickHouse.getInstance()
					.storeMetadataExt(resourceID, resourcePath, duration, count, title, audioUrl);
			if (db != PanakoStorageClickHouse.getInstance()) {
				db.storeMetadata(resourceID, resourcePath, duration, count);
			}
		} else {
			db.storeMetadata(resourceID, resourcePath, duration, count);
		}
	}

	/** Render a possibly-null string as a JSON value ({@code null} or {@code "..."}). */
	static String jsonNullableString(String value) {
		if (value == null) return "null";
		return "\"" + HttpUtil.escapeJson(value) + "\"";
	}

	static OlafStorage getOlafStorage() {
		OlafStorage db;
		if (Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("LMDB")) {
			db = OlafStorageKV.getInstance();
		} else if (Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
			db = OlafStorageClickHouse.getInstance();
		} else if (Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("FILE")) {
			db = OlafStorageFile.getInstance();
		} else {
			db = OlafStorageMemory.getInstance();
		}
		if (Config.getBoolean(Key.OLAF_CACHE_TO_FILE) && db != OlafStorageFile.getInstance()) {
			db = new OlafCachingStorage(OlafStorageFile.getInstance(), db);
		}
		return db;
	}

	static PanakoStorage getPanakoStorage() {
		PanakoStorage db;
		if (Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("LMDB")) {
			db = PanakoStorageKV.getInstance();
		} else if (Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("FILE")) {
			db = PanakoStorageFile.getInstance();
		} else {
			db = PanakoStorageMemory.getInstance();
		}
		if (Config.getBoolean(Key.PANAKO_CACHE_TO_FILE) && db != PanakoStorageFile.getInstance()) {
			db = new PanakoCachingStorage(PanakoStorageFile.getInstance(), db);
		}
		return db;
	}

	static int findMatchingBracket(String s, int openPos) {
		if (openPos < 0 || openPos >= s.length()) return -1;
		int depth = 0;
		boolean inString = false;
		for (int i = openPos; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
				inString = !inString;
			} else if (!inString) {
				if (c == '[') depth++;
				else if (c == ']') {
					depth--;
					if (depth == 0) return i;
				}
			}
		}
		return -1;
	}

	static String extractJsonString(String json, String key) {
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

	static long extractJsonLong(String json, String key, long defaultValue) {
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx == -1) return defaultValue;
		int colon = json.indexOf(':', idx + search.length());
		if (colon == -1) return defaultValue;
		int start = colon + 1;
		while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
		int end = start;
		while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
		if (end == start) return defaultValue;
		try { return Long.parseLong(json.substring(start, end)); } catch (NumberFormatException e) { return defaultValue; }
	}

	static int extractJsonInt(String json, String key, int defaultValue) {
		return (int) extractJsonLong(json, key, defaultValue);
	}

	static double extractJsonDouble(String json, String key, double defaultValue) {
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx == -1) return defaultValue;
		int colon = json.indexOf(':', idx + search.length());
		if (colon == -1) return defaultValue;
		int start = colon + 1;
		while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
		int end = start;
		while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
		if (end == start) return defaultValue;
		try { return Double.parseDouble(json.substring(start, end)); } catch (NumberFormatException e) { return defaultValue; }
	}
}
