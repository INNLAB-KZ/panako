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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/store — stores audio fingerprints from an uploaded file.
 *
 * <p>A write lock serializes the entire store operation because the underlying
 * LMDB storage uses plain HashMap (not thread-safe) for internal queues.</p>
 *
 * <p>The multipart body must include an {@code audio} file part. Two optional
 * text parts — {@code title} and {@code audio_url} — describe the track for
 * deployments that index audio without an ISRC; both are persisted to the
 * ClickHouse metadata table when ClickHouse storage is configured.</p>
 */
public class StoreHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(StoreHandler.class.getName());

	private final Strategy strategy;
	private final ReentrantLock writeLock;
	private final long maxBytes;
	private final MatchValidator validator = new MatchValidator();

	/** match_percentage threshold above which the upload is treated as a duplicate of an already-indexed track. */
	private static final double CONTENT_DUP_MATCH_PERCENTAGE = 99.0;

	public StoreHandler(Strategy strategy, ReentrantLock writeLock, int maxUploadSizeMB) {
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

		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		if (contentType == null || !contentType.contains("multipart/form-data")) {
			HttpUtil.sendError(exchange, 400, "Content-Type must be multipart/form-data");
			return;
		}

		MultipartParser.UploadedFile upload = null;
		Path audioFile = null;
		try {
			upload = MultipartParser.parse(exchange.getRequestBody(), contentType, maxBytes);
			if (upload == null) {
				HttpUtil.sendError(exchange, 400, "No audio file found in request (field name: audio)");
				return;
			}

			// Rename temp file to original filename so that:
			// 1) FileUtils.getIdentifier() produces a stable ID based on the real name
			// 2) metadata.path stored in LMDB contains the real filename
			audioFile = upload.tempFile.resolveSibling(upload.fileName);
			Files.move(upload.tempFile, audioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

			String filePath = audioFile.toAbsolutePath().toString();
			int identifier = FileUtils.getIdentifier(filePath);
			String isrc = HttpUtil.extractIsrc(upload.fileName);
			String title = upload.formFields.get("title");
			String audioUrl = upload.formFields.get("audio_url");

			// Skip indexing when the uploaded audio is empty.
			if (Files.size(audioFile) == 0) {
				LOG.warning("Empty audio (0 bytes) for " + upload.fileName + " — skipping indexing");
				StringBuilder json = new StringBuilder();
				json.append("{");
				json.append("\"status\":\"skipped_empty\",");
				json.append("\"identifier\":").append(identifier).append(",");
				json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
				json.append("\"filename\":\"").append(HttpUtil.escapeJson(upload.fileName)).append("\",");
				json.append("\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title)).append(",");
				json.append("\"audio_url\":").append(StoreFingerprintsHandler.jsonNullableString(audioUrl));
				json.append("}");
				HttpUtil.sendJson(exchange, 200, json.toString());
				return;
			}

			// Check for duplicates — verify integrity of existing data
			if (strategy.hasResource(filePath)) {
				double[] meta = HttpUtil.parseMetadata(strategy.metadata(filePath));
				if (meta != null && meta[0] > 0 && meta[1] > 0) {
					// Existing data looks complete — return duplicate
					StringBuilder json = new StringBuilder();
					json.append("{");
					json.append("\"status\":\"already_exists\",");
					json.append("\"identifier\":").append(identifier).append(",");
					json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
					json.append("\"filename\":\"").append(HttpUtil.escapeJson(upload.fileName)).append("\",");
					json.append("\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title)).append(",");
					json.append("\"audio_url\":").append(StoreFingerprintsHandler.jsonNullableString(audioUrl)).append(",");
					json.append("\"duration_seconds\":").append(String.format("%.1f", meta[0])).append(",");
					json.append("\"fingerprints_count\":").append((int) meta[1]);
					json.append("}");
					HttpUtil.sendJson(exchange, 200, json.toString());
					return;
				}
				// Incomplete data from interrupted store — delete and re-store
				LOG.warning("Incomplete data for " + upload.fileName + " — deleting and re-storing");
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
				LOG.info("Content duplicate for " + upload.fileName
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
				json.append("\"submitted_filename\":\"").append(HttpUtil.escapeJson(upload.fileName)).append("\",");
				json.append("\"submitted_isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\"");
				json.append("}");
				HttpUtil.sendJson(exchange, 200, json.toString());
				return;
			}

			long startTime = System.currentTimeMillis();

			writeLock.lock();
			double durationInSeconds;
			try {
				durationInSeconds = strategy.store(filePath, upload.fileName);
			} finally {
				writeLock.unlock();
			}

			long processingTimeMs = System.currentTimeMillis() - startTime;
			int fingerprintCount = (int) Math.round(durationInSeconds * 7);

			persistExtras(identifier, upload.fileName, (float) durationInSeconds, fingerprintCount, title, audioUrl);

			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"status\":\"ok\",");
			json.append("\"identifier\":").append(identifier).append(",");
			json.append("\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\",");
			json.append("\"filename\":\"").append(HttpUtil.escapeJson(upload.fileName)).append("\",");
			json.append("\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title)).append(",");
			json.append("\"audio_url\":").append(StoreFingerprintsHandler.jsonNullableString(audioUrl)).append(",");
			json.append("\"duration_seconds\":").append(String.format("%.1f", durationInSeconds)).append(",");
			json.append("\"fingerprints_count\":").append(fingerprintCount).append(",");
			json.append("\"processing_time_ms\":").append(processingTimeMs);
			json.append("}");

			HttpUtil.sendJson(exchange, 200, json.toString());

		} catch (IOException e) {
			LOG.log(Level.WARNING, "Store request failed", e);
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Store request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
			}
			if (upload != null) {
				try { Files.deleteIfExists(upload.tempFile); } catch (IOException ignored) {}
			}
		}
	}

	/**
	 * Persist the optional {@code title} and {@code audio_url} extras to the
	 * ClickHouse-backed metadata table after the upstream strategy has already
	 * written the base row. ReplacingMergeTree deduplicates the second
	 * {@code INSERT} on its next background merge.
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
}
