package be.panako.http;

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
