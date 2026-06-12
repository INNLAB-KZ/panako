package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.QueryResultHandler;
import be.panako.strategy.Strategy;
import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.strategy.panako.storage.PanakoStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.FileUtils;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/store/url — stores audio fingerprints by downloading from a URL.
 *
 * <p>Accepts JSON: {@code {"audio_url": "https://example.com/track.mp3", "filename": "track.mp3",
 * "title": "Artist:Track"}}</p>
 * <p>The {@code filename} field is optional. If omitted, it is derived from the URL path.</p>
 * <p>The {@code title} field is optional descriptive metadata. Together with {@code audio_url}
 * it is persisted to the ClickHouse metadata table when ClickHouse storage is configured.</p>
 */
public class StoreUrlHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(StoreUrlHandler.class.getName());

	private final Strategy strategy;
	private final ReentrantLock writeLock;
	private final long maxBytes;
	private final MatchValidator validator = new MatchValidator();

	/** match_percentage threshold above which the upload is treated as a duplicate of an already-indexed track. */
	private static final double CONTENT_DUP_MATCH_PERCENTAGE = 99.0;

	public StoreUrlHandler(Strategy strategy, ReentrantLock writeLock, int maxUploadSizeMB) {
		this.strategy = strategy;
		this.writeLock = writeLock;
		this.maxBytes = maxUploadSizeMB * 1024L * 1024L;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}

		Path audioFile = null;
		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

			String audioUrl = extractJsonString(body, "audio_url");
			if (audioUrl == null || audioUrl.isEmpty()) {
				HttpUtil.sendError(exchange, 400, "Missing 'audio_url' in JSON body");
				return;
			}

			String filename = extractJsonString(body, "filename");
			if (filename == null || filename.isEmpty()) {
				filename = filenameFromUrl(audioUrl);
			}
			if (filename == null || filename.isEmpty()) {
				filename = "audio.mp3";
			}

			String title = extractJsonString(body, "title");

			// Download the audio file
			Path tempFile = Files.createTempFile("panako_dl_", "_" + filename);
			try {
				HttpUtil.downloadWithRetry(audioUrl, tempFile, maxBytes);
			} catch (IOException e) {
				Files.deleteIfExists(tempFile);
				HttpUtil.sendError(exchange, 400, "Failed to download audio: " + e.getMessage());
				return;
			}

			// Rename to original filename for stable identifier
			audioFile = tempFile.resolveSibling(filename);
			Files.move(tempFile, audioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

			String filePath = audioFile.toAbsolutePath().toString();
			int identifier = FileUtils.getIdentifier(filePath);
			String isrc = HttpUtil.extractIsrc(filename);

			// Skip indexing when the downloaded audio is empty.
			if (Files.size(audioFile) == 0) {
				LOG.warning("Empty audio (0 bytes) from " + audioUrl + " — skipping indexing");
				StringBuilder json = new StringBuilder();
				json.append("{");
				json.append("\"status\":\"skipped_empty\",");
				json.append("\"identifier\":").append(identifier).append(",");
				json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
				json.append("\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\",");
				json.append("\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title)).append(",");
				json.append("\"audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\"");
				json.append("}");
				HttpUtil.sendJson(exchange, 200, json.toString());
				return;
			}

			// Check for duplicates — verify integrity of existing data
			if (strategy.hasResource(filePath)) {
				double[] meta = HttpUtil.parseMetadata(strategy.metadata(filePath));
				if (meta != null && meta[0] > 0 && meta[1] > 0) {
					StringBuilder json = new StringBuilder();
					json.append("{");
					json.append("\"status\":\"already_exists\",");
					json.append("\"identifier\":").append(identifier).append(",");
					json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
					json.append("\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\",");
					json.append("\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title)).append(",");
					json.append("\"audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\",");
					json.append("\"duration_seconds\":").append(String.format("%.1f", meta[0])).append(",");
					json.append("\"fingerprints_count\":").append((int) meta[1]);
					json.append("}");
					HttpUtil.sendJson(exchange, 200, json.toString());
					return;
				}
				LOG.warning("Incomplete data for " + filename + " — deleting and re-storing");
				writeLock.lock();
				try {
					strategy.delete(filePath);
				} finally {
					writeLock.unlock();
				}
			}

			// Content-based duplicate check: same audio already indexed under a different
			// path/ISRC. Query the index and short-circuit when a validated match reports
			// at least CONTENT_DUP_MATCH_PERCENTAGE of seconds covered.
			QueryResult contentDup = findContentDuplicate(filePath);
			if (contentDup != null) {
				LOG.info("Content duplicate for " + filename
						+ " — matches refId=" + contentDup.refIdentifier
						+ " at " + String.format("%.1f", contentDup.percentOfSecondsWithMatches) + "%");
				String matchedIsrc = HttpUtil.extractIsrc(contentDup.refPath);
				String[] extras = QueryHandler.lookupExtras(contentDup.refIdentifier);
				StringBuilder json = new StringBuilder();
				json.append("{");
				json.append("\"status\":\"already_indexed_match\",");
				json.append("\"identifier\":").append(contentDup.refIdentifier).append(",");
				json.append("\"isrc\":\"").append(HttpUtil.escapeJson(matchedIsrc)).append("\",");
				json.append("\"filename\":\"").append(HttpUtil.escapeJson(contentDup.refPath)).append("\",");
				json.append("\"title\":").append(StoreFingerprintsHandler.jsonNullableString(extras[0])).append(",");
				json.append("\"audio_url\":").append(StoreFingerprintsHandler.jsonNullableString(extras[1])).append(",");
				json.append("\"match_percentage\":").append(String.format("%.1f", contentDup.percentOfSecondsWithMatches)).append(",");
				json.append("\"submitted_filename\":\"").append(HttpUtil.escapeJson(filename)).append("\",");
				json.append("\"submitted_audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\",");
				json.append("\"submitted_isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\"");
				json.append("}");
				HttpUtil.sendJson(exchange, 200, json.toString());
				return;
			}

			long startTime = System.currentTimeMillis();

			writeLock.lock();
			double durationInSeconds;
			try {
				durationInSeconds = strategy.store(filePath, filename);
			} finally {
				writeLock.unlock();
			}

			long processingTimeMs = System.currentTimeMillis() - startTime;
			int fingerprintCount = (int) Math.round(durationInSeconds * 7);

			persistExtras(identifier, filename, (float) durationInSeconds, fingerprintCount, title, audioUrl);

			StringBuilder json = new StringBuilder();
			json.append("{");

			json.append("\"status\":\"ok\",");
			json.append("\"identifier\":").append(identifier).append(",");
			json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
			json.append("\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\",");
			json.append("\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title)).append(",");
			json.append("\"audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\",");
			json.append("\"duration_seconds\":").append(String.format("%.1f", durationInSeconds)).append(",");
			json.append("\"fingerprints_count\":").append(fingerprintCount).append(",");
			json.append("\"processing_time_ms\":").append(processingTimeMs);
			json.append("}");

			HttpUtil.sendJson(exchange, 200, json.toString());

		} catch (IOException e) {
			LOG.log(Level.WARNING, "Store URL request failed", e);
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Store URL request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
			}
		}
	}

	/**
	 * Persist the optional {@code title} and {@code audio_url} extras to the
	 * ClickHouse-backed metadata table for the configured strategy. The upstream
	 * {@link Strategy#store} call has already inserted the base metadata row;
	 * this issues a second {@code INSERT} with the full column set so
	 * {@code ReplacingMergeTree} replaces it on the next background merge.
	 *
	 * <p>No-op when ClickHouse is not the active storage backend, or when both
	 * extras are {@code null} / empty.</p>
	 */
	/**
	 * Run a fingerprint query against the existing index. Return the first validated
	 * match whose {@code percentOfSecondsWithMatches} is at least
	 * {@link #CONTENT_DUP_MATCH_PERCENTAGE}, or {@code null} when no such match
	 * exists.
	 */
	private QueryResult findContentDuplicate(String filePath) {
		int maxResults = Config.getInt(Key.NUMBER_OF_QUERY_RESULTS);
		List<QueryResult> results = new ArrayList<>();
		strategy.query(filePath, maxResults, new HashSet<>(), new QueryResultHandler() {
			@Override public void handleQueryResult(QueryResult r) { results.add(r); }
			@Override public void handleEmptyResult(QueryResult r) { }
		});
		if (results.isEmpty()) return null;
		double queryDuration = results.get(0).queryStop > 0 ? results.get(0).queryStop : 0;
		for (QueryResult r : results) {
			if (!validator.validateQuality(r)) continue;
			if (!validator.validate(r, queryDuration)) continue;
			double refDuration = r.refStop > 0 ? r.refStop : 0;
			if (!validator.validateDuration(r, queryDuration, refDuration)) continue;
			if (r.percentOfSecondsWithMatches >= CONTENT_DUP_MATCH_PERCENTAGE) {
				return r;
			}
		}
		return null;
	}

	private static void persistExtras(int identifier, String filename, float duration,
									  int fingerprintCount, String title, String audioUrl) {
		boolean titlePresent = title != null && !title.isEmpty();
		boolean urlPresent = audioUrl != null && !audioUrl.isEmpty();
		if (!titlePresent && !urlPresent) return;

		boolean isOlaf = Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF");
		if (isOlaf) {
			if (Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				OlafStorageClickHouse.getInstance().storeMetadataExt(
						identifier, filename, duration, fingerprintCount,
						titlePresent ? title : null, urlPresent ? audioUrl : null);
			}
		} else {
			if (Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				PanakoStorageClickHouse.getInstance().storeMetadataExt(
						identifier, filename, duration, fingerprintCount,
						titlePresent ? title : null, urlPresent ? audioUrl : null);
			}
		}
	}

	private static String filenameFromUrl(String urlString) {
		try {
			String path = URI.create(urlString).getPath();
			if (path == null || path.isEmpty() || path.equals("/")) return null;
			int lastSlash = path.lastIndexOf('/');
			String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
			// Remove query-like artifacts
			int q = name.indexOf('?');
			if (q > 0) name = name.substring(0, q);
			return name.isEmpty() ? null : name;
		} catch (Exception e) {
			return null;
		}
	}

	private static String extractJsonString(String json, String key) {
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx == -1) return null;
		int colon = json.indexOf(':', idx + search.length());
		if (colon == -1) return null;
		// Find opening quote
		int start = json.indexOf('"', colon + 1);
		if (start == -1) return null;
		start++;
		// Find closing quote (handle escaped quotes)
		int end = start;
		while (end < json.length()) {
			if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
			end++;
		}
		if (end >= json.length()) return null;
		return json.substring(start, end);
	}
}
