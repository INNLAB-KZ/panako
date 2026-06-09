package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.Strategy;
import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.strategy.panako.storage.PanakoStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.FileUtils;
import be.panako.util.Key;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.StreamEntryID;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis Streams consumer/producer for async store and monitor operations.
 *
 * <p>Parallel to {@link PanakoKafkaWorker} but uses Redis Streams as the queue
 * instead of Kafka. Stream names match the Kafka topic names so the same
 * orchestrator schema works for both backends.</p>
 *
 * <p>Per-task lifecycle:
 * <ol>
 *   <li>{@code XREADGROUP} claims one pending entry (blocks up to 5s).</li>
 *   <li>Worker processes the task (store / monitor).</li>
 *   <li>{@code XADD} publishes the result to the response stream.</li>
 *   <li>{@code XACK} acknowledges the input message.</li>
 * </ol>
 * On crash, the message stays in the consumer group's Pending Entries List
 * and is reclaimed via {@code XAUTOCLAIM} after {@code REDIS_CLAIM_IDLE_MS}
 * (default 10 min).</p>
 *
 * <p>Stream names (configurable via env, defaults shown):
 * <ul>
 *   <li>{@code panako.monitor.request} / {@code panako.monitor.response}</li>
 *   <li>{@code panako.monitor.refine.request} / {@code panako.monitor.refine.response} (refine mode)</li>
 *   <li>{@code panako-store-requests} / {@code panako-store-results}</li>
 * </ul>
 * </p>
 */
public class PanakoRedisStreamWorker implements Runnable {

	private static final Logger LOG = Logger.getLogger(PanakoRedisStreamWorker.class.getName());

	public static final String MODE_STAGE1 = PanakoKafkaWorker.MODE_STAGE1;
	public static final String MODE_REFINE = PanakoKafkaWorker.MODE_REFINE;
	public static final String MODE_BOTH = PanakoKafkaWorker.MODE_BOTH;

	private static final String DEFAULT_GROUP = "panako-workers";
	private static final String REFINE_GROUP = "panako-worker-refine";
	private static final String REFINE_REQUEST_STREAM = "panako.monitor.refine.request";
	private static final String REFINE_RESPONSE_STREAM = "panako.monitor.refine.response";

	private final Strategy strategy;
	private final ReentrantLock writeLock;
	private final long maxBytes;
	private final String workerMode;
	private final String groupId;
	private final String consumerName;

	private final String storeRequestStream;
	private final String storeResultStream;
	private final String monitorRequestStream;
	private final String monitorResultStream;

	private final List<String> subscribedStreams;

	private final JedisPool pool;
	private final long claimIdleMs;
	private final long blockMs;

	private volatile boolean running = true;
	private Thread reclaimThread;

	private static String getEnvOrDefault(String name, String defaultValue) {
		String val = System.getProperty(name);
		if (val == null) val = System.getenv(name);
		if (val == null || val.trim().isEmpty()) return defaultValue;
		return val.trim();
	}

	public PanakoRedisStreamWorker(Strategy strategy, ReentrantLock writeLock,
								   int maxUploadSizeMB, String workerMode) {
		this.strategy = strategy;
		this.writeLock = writeLock;
		this.maxBytes = (long) maxUploadSizeMB * 1024L * 1024L;
		this.workerMode = workerMode == null ? MODE_STAGE1 : workerMode.toLowerCase();

		String mode = Config.get(Key.KAFKA_MODE).toUpperCase();
		if (this.workerMode.equals(MODE_REFINE)) {
			this.storeRequestStream = "";
			this.storeResultStream = "";
			this.monitorRequestStream = REFINE_REQUEST_STREAM;
			this.monitorResultStream = REFINE_RESPONSE_STREAM;
			this.groupId = REFINE_GROUP;
		} else {
			this.storeRequestStream = Config.get(Key.KAFKA_STORE_REQUEST_TOPIC);
			this.storeResultStream = Config.get(Key.KAFKA_STORE_RESULT_TOPIC);
			this.monitorRequestStream = Config.get(Key.KAFKA_REQUEST_TOPIC);
			this.monitorResultStream = Config.get(Key.KAFKA_RESPONSE_TOPIC);
			this.groupId = getEnvOrDefault("REDIS_GROUP_ID", DEFAULT_GROUP);
		}

		List<String> streams = new ArrayList<>();
		if (this.workerMode.equals(MODE_REFINE)) {
			streams.add(monitorRequestStream);
		} else {
			if (mode.equals("ALL") || mode.equals("STORE")) streams.add(storeRequestStream);
			if (mode.equals("ALL") || mode.equals("MONITOR")) streams.add(monitorRequestStream);
		}
		this.subscribedStreams = streams;

		String host = getEnvOrDefault("REDIS_HOST", "127.0.0.1");
		int port = Integer.parseInt(getEnvOrDefault("REDIS_PORT", "6379"));
		String password = getEnvOrDefault("REDIS_PASSWORD", "");
		int poolSize = Integer.parseInt(getEnvOrDefault("REDIS_POOL_SIZE", "8"));
		int timeoutMs = Integer.parseInt(getEnvOrDefault("REDIS_TIMEOUT_MS", "10000"));

		this.claimIdleMs = Long.parseLong(getEnvOrDefault("REDIS_CLAIM_IDLE_MS", "600000")); // 10 min
		this.blockMs = Long.parseLong(getEnvOrDefault("REDIS_BLOCK_MS", "5000"));

		JedisPoolConfig poolConfig = new JedisPoolConfig();
		poolConfig.setMaxTotal(poolSize);
		poolConfig.setMaxIdle(poolSize);
		poolConfig.setMinIdle(1);
		poolConfig.setTestOnBorrow(false);
		poolConfig.setTestWhileIdle(true);

		redis.clients.jedis.DefaultJedisClientConfig.Builder cfgBuilder = redis.clients.jedis.DefaultJedisClientConfig.builder()
				.connectionTimeoutMillis(timeoutMs)
				.socketTimeoutMillis(timeoutMs);
		if (!password.isEmpty()) {
			cfgBuilder.password(password);
		}
		this.pool = new JedisPool(poolConfig, new HostAndPort(host, port), cfgBuilder.build());

		String hostName;
		try {
			hostName = java.net.InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			hostName = "panako";
		}
		this.consumerName = hostName + "-" + ProcessHandle.current().pid() + "-" + Thread.currentThread().getId();

		// Create consumer groups (idempotent — ignore BUSYGROUP)
		try (Jedis jedis = pool.getResource()) {
			for (String stream : subscribedStreams) {
				try {
					jedis.xgroupCreate(stream, groupId, StreamEntryID.XGROUP_LAST_ENTRY, true);
					LOG.info("Created Redis consumer group '" + groupId + "' on stream '" + stream + "'");
				} catch (Exception e) {
					if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
						LOG.fine("Consumer group '" + groupId + "' already exists on '" + stream + "'");
					} else {
						LOG.log(Level.WARNING, "Failed to create consumer group on '" + stream + "'", e);
					}
				}
			}
		}
	}

	public void stop() {
		running = false;
		if (reclaimThread != null) reclaimThread.interrupt();
		try { pool.close(); } catch (Exception ignored) {}
	}

	@Override
	public void run() {
		LOG.info("Redis worker started (worker_mode=" + workerMode + ", group=" + groupId
				+ ", consumer=" + consumerName + ") — listening on " + subscribedStreams);

		// Background thread: periodic XAUTOCLAIM for stuck messages from dead consumers
		reclaimThread = new Thread(this::reclaimLoop, "panako-redis-reclaim-" + workerMode);
		reclaimThread.setDaemon(true);
		reclaimThread.start();

		// Build XREADGROUP map: each stream → ">" (only new undelivered messages)
		Map<String, StreamEntryID> streamsMap = new HashMap<>();
		for (String s : subscribedStreams) {
			streamsMap.put(s, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY);
		}

		while (running) {
			try (Jedis jedis = pool.getResource()) {
				List<Map.Entry<String, List<StreamEntry>>> response = jedis.xreadGroup(
						groupId, consumerName,
						XReadGroupParams.xReadGroupParams().count(1).block((int) blockMs),
						streamsMap);

				if (response == null || response.isEmpty()) continue;

				for (Map.Entry<String, List<StreamEntry>> streamEntries : response) {
					String stream = streamEntries.getKey();
					for (StreamEntry entry : streamEntries.getValue()) {
						processEntry(jedis, stream, entry);
					}
				}
			} catch (Exception e) {
				LOG.log(Level.WARNING, "Redis read loop error — will retry after 5s (worker stays alive)", e);
				try { Thread.sleep(5000); } catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		LOG.info("Redis worker stopped");
	}

	private void processEntry(Jedis jedis, String stream, StreamEntry entry) {
		Map<String, String> fields = entry.getFields();
		String body = fields.get("value");
		if (body == null) body = fields.getOrDefault("payload", "");
		String key = fields.get("key");

		try {
			if (stream.equals(storeRequestStream)) {
				handleStoreRequest(key, body);
			} else if (stream.equals(monitorRequestStream)) {
				handleMonitorRequest(key, body);
			} else {
				LOG.warning("Unknown stream: " + stream);
			}
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Redis message processing failed", e);
			String responseStream = stream.equals(storeRequestStream) ? storeResultStream : monitorResultStream;
			try {
				sendError(responseStream, key, e.getMessage());
			} catch (Exception sendErr) {
				LOG.log(Level.WARNING, "Failed to send error response", sendErr);
			}
		} finally {
			try {
				jedis.xack(stream, groupId, entry.getID());
			} catch (Exception ackErr) {
				LOG.log(Level.WARNING, "Failed to XACK " + entry.getID(), ackErr);
			}
		}
	}

	private void reclaimLoop() {
		while (running) {
			try {
				Thread.sleep(60_000);
				try (Jedis jedis = pool.getResource()) {
					for (String stream : subscribedStreams) {
						reclaimStuck(jedis, stream);
					}
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				LOG.log(Level.FINE, "Reclaim loop iteration failed", e);
			}
		}
	}

	private void reclaimStuck(Jedis jedis, String stream) {
		try {
			Map.Entry<StreamEntryID, List<StreamEntry>> reclaimed = jedis.xautoclaim(
					stream, groupId, consumerName, claimIdleMs, new StreamEntryID(0, 0),
					XAutoClaimParams.xAutoClaimParams().count(10));

			if (reclaimed == null || reclaimed.getValue() == null || reclaimed.getValue().isEmpty()) return;

			LOG.info("Reclaimed " + reclaimed.getValue().size() + " stuck messages from '" + stream + "'");
			for (StreamEntry entry : reclaimed.getValue()) {
				processEntry(jedis, stream, entry);
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "XAUTOCLAIM failed on " + stream, e);
		}
	}

	// ─────────────────────────────────────────────────────────────────────
	// Task handlers — mirror PanakoKafkaWorker.handleStoreRequest / handleMonitorRequest
	// ─────────────────────────────────────────────────────────────────────

	private void handleStoreRequest(String key, String body) {
		String audioUrl = extractJsonString(body, "audio_url");
		String filename = extractJsonString(body, "filename");
		String title = extractJsonString(body, "title");
		String requestId = extractJsonString(body, "recording_id");
		if (requestId == null) requestId = extractJsonString(body, "request_id");
		if (requestId == null) requestId = key;

		if (audioUrl == null || audioUrl.isEmpty()) {
			sendError(storeResultStream, requestId, "Missing 'audio_url'");
			return;
		}
		if (filename == null || filename.isEmpty()) {
			filename = filenameFromUrl(audioUrl);
		}
		if (filename == null || filename.isEmpty()) {
			filename = "audio.mp3";
		}

		Path audioFile = null;
		try {
			Path tempFile = Files.createTempFile("panako_redis_dl_", "_" + filename);
			try {
				downloadFile(audioUrl, tempFile, maxBytes);
			} catch (IOException e) {
				Files.deleteIfExists(tempFile);
				sendError(storeResultStream, requestId, "Download failed: " + e.getMessage());
				return;
			}

			audioFile = tempFile.resolveSibling(filename);
			Files.move(tempFile, audioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

			String filePath = audioFile.toAbsolutePath().toString();
			int identifier = FileUtils.getIdentifier(filePath);
			String isrc = HttpUtil.extractIsrc(filename);

			if (strategy.hasResource(filePath)) {
				double[] meta = HttpUtil.parseMetadata(strategy.metadata(filePath));
				if (meta != null && meta[0] > 0 && meta[1] > 0) {
					StringBuilder json = new StringBuilder();
					json.append("{\"status\":\"already_exists\"");
					json.append(",\"request_id\":\"").append(HttpUtil.escapeJson(requestId != null ? requestId : "")).append("\"");
					json.append(",\"identifier\":").append(identifier);
					json.append(",\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\"");
					json.append(",\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\"");
					json.append(",\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title));
					json.append(",\"audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\"");
					json.append(",\"duration_seconds\":").append(String.format("%.1f", meta[0]));
					json.append(",\"fingerprints_count\":").append((int) meta[1]);
					json.append("}");
					send(storeResultStream, requestId, json.toString());
					return;
				}
				writeLock.lock();
				try { strategy.delete(filePath); } finally { writeLock.unlock(); }
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
			int fpCount = (int) Math.round(durationInSeconds * 7);

			persistExtras(identifier, filename, (float) durationInSeconds, fpCount, title, audioUrl);

			StringBuilder json = new StringBuilder();
			json.append("{\"status\":\"ok\"");
			json.append(",\"request_id\":\"").append(HttpUtil.escapeJson(requestId != null ? requestId : "")).append("\"");
			json.append(",\"identifier\":").append(identifier);
			json.append(",\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\"");
			json.append(",\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\"");
			json.append(",\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title));
			json.append(",\"audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\"");
			json.append(",\"duration_seconds\":").append(String.format("%.1f", durationInSeconds));
			json.append(",\"fingerprints_count\":").append(fpCount);
			json.append(",\"processing_time_ms\":").append(processingTimeMs);
			json.append("}");
			send(storeResultStream, requestId, json.toString());
			LOG.info("Redis store completed: " + filename + " in " + processingTimeMs + "ms");

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Redis store failed for " + audioUrl, e);
			sendError(storeResultStream, requestId, e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
			}
		}
	}

	private void handleMonitorRequest(String key, String body) {
		String audioUrl = extractJsonString(body, "audio_url");
		String recordingId = extractJsonString(body, "recording_id");
		if (recordingId == null) recordingId = key;

		if (audioUrl == null || audioUrl.isEmpty()) {
			sendError(monitorResultStream, recordingId, "Missing 'audio_url'");
			return;
		}

		String filename = extractJsonString(body, "filename");
		if (filename == null || filename.isEmpty()) {
			filename = filenameFromUrl(audioUrl);
		}
		if (filename == null || filename.isEmpty()) {
			filename = "audio.mp3";
		}

		List<int[]> segments = extractSegments(body);

		Path audioFile = null;
		try {
			Path tempFile = Files.createTempFile("panako_redis_monitor_", "_" + filename);
			try {
				downloadFile(audioUrl, tempFile, maxBytes);
			} catch (IOException e) {
				Files.deleteIfExists(tempFile);
				sendError(monitorResultStream, recordingId, "Download failed: " + e.getMessage());
				return;
			}
			audioFile = tempFile;

			long startTime = System.currentTimeMillis();
			String filePath = audioFile.toAbsolutePath().toString();

			double totalDuration = MonitorHandler.getAudioDuration(filePath);
			List<QueryResult> allResults;

			if (segments != null && !segments.isEmpty()) {
				allResults = new ArrayList<>();
				for (int[] seg : segments) {
					int segStart = seg[0];
					int segDuration = seg[1] - seg[0];
					if (segDuration <= 0) continue;

					Path chunk = MonitorHandler.extractAudioChunkDouble(filePath, segStart, segDuration);
					try {
						List<QueryResult> segResults = MonitorHandler.monitorWithAbsoluteTimes(strategy, chunk.toAbsolutePath().toString());
						for (QueryResult r : segResults) {
							allResults.add(new QueryResult(
									r.queryPath, r.queryStart + segStart, r.queryStop + segStart,
									r.refPath, r.refIdentifier, r.refStart, r.refStop,
									r.score, r.timeFactor, r.frequencyFactor,
									r.percentOfSecondsWithMatches));
						}
					} finally {
						try { Files.deleteIfExists(chunk); } catch (IOException ignored) {}
					}
				}
				LOG.info("Redis monitor (segments mode): " + segments.size() + " segments for " + recordingId);
			} else {
				allResults = MonitorHandler.monitorWithAbsoluteTimes(strategy, filePath);
			}

			long processingTimeMs = System.currentTimeMillis() - startTime;

			String monitorJson = MonitorHandler.buildResponseJson(strategy, allResults, filePath, processingTimeMs);
			String json = "{\"recording_id\":\"" + HttpUtil.escapeJson(recordingId != null ? recordingId : "") + "\"," +
					"\"duration_seconds\":" + String.format("%.1f", totalDuration) + "," +
					monitorJson.substring(1);

			send(monitorResultStream, recordingId, json);
			LOG.info("Redis monitor completed: " + filename + " in " + processingTimeMs + "ms");

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Redis monitor failed for " + audioUrl, e);
			sendError(monitorResultStream, recordingId, e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
			}
		}
	}

	private void send(String stream, String key, String value) {
		try (Jedis jedis = pool.getResource()) {
			Map<String, String> fields = new HashMap<>();
			fields.put("key", key != null ? key : "");
			fields.put("value", value);
			jedis.xadd(stream, XAddParams.xAddParams().maxLen(100000).approximateTrimming(), fields);
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Failed to XADD to stream " + stream, e);
		}
	}

	private void sendError(String stream, String key, String message) {
		String json = "{\"status\":\"error\"" +
				",\"recording_id\":\"" + HttpUtil.escapeJson(key != null ? key : "") + "\"" +
				",\"error\":\"" + HttpUtil.escapeJson(message != null ? message : "unknown") + "\"}";
		send(stream, key, json);
	}

	// ─────────────────────────────────────────────────────────────────────
	// Helpers (duplicated from PanakoKafkaWorker to avoid breaking encapsulation)
	// ─────────────────────────────────────────────────────────────────────

	private static List<int[]> extractSegments(String json) {
		if (json == null) return null;
		int idx = json.indexOf("\"segments\"");
		if (idx == -1) return null;
		int arrStart = json.indexOf('[', idx);
		if (arrStart == -1) return null;
		int arrEnd = json.indexOf(']', arrStart);
		if (arrEnd == -1) return null;
		String arrStr = json.substring(arrStart + 1, arrEnd);
		if (arrStr.trim().isEmpty()) return null;
		List<int[]> segments = new ArrayList<>();
		String[] parts = arrStr.split("\\},\\s*\\{");
		for (String part : parts) {
			part = part.replace("{", "").replace("}", "").trim();
			int startSec = extractInt(part, "start_seconds");
			int endSec = extractInt(part, "end_seconds");
			if (startSec >= 0 && endSec > startSec) {
				segments.add(new int[]{startSec, endSec});
			}
		}
		return segments.isEmpty() ? null : segments;
	}

	private static int extractInt(String fragment, String key) {
		int idx = fragment.indexOf("\"" + key + "\"");
		if (idx == -1) return -1;
		int colon = fragment.indexOf(':', idx);
		if (colon == -1) return -1;
		StringBuilder num = new StringBuilder();
		for (int i = colon + 1; i < fragment.length(); i++) {
			char c = fragment.charAt(i);
			if (c == ' ' || c == '\t') continue;
			if (c >= '0' && c <= '9') num.append(c);
			else if (num.length() > 0) break;
		}
		if (num.length() == 0) return -1;
		try { return Integer.parseInt(num.toString()); }
		catch (NumberFormatException e) { return -1; }
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

	private void downloadFile(String urlString, Path target, long maxBytes) throws IOException {
		URL url = URI.create(urlString).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(10_000);
		conn.setReadTimeout(60_000);
		conn.setInstanceFollowRedirects(true);
		int status = conn.getResponseCode();
		if (status < 200 || status >= 300) {
			conn.disconnect();
			throw new IOException("HTTP " + status + " from " + urlString);
		}
		try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
			byte[] buf = new byte[8192];
			long total = 0;
			int read;
			while ((read = in.read(buf)) != -1) {
				total += read;
				if (total > maxBytes) throw new IOException("Download exceeds max size");
				out.write(buf, 0, read);
			}
		} finally {
			conn.disconnect();
		}
	}

	private static String filenameFromUrl(String urlString) {
		try {
			String path = URI.create(urlString).getPath();
			if (path == null || path.isEmpty() || path.equals("/")) return null;
			int lastSlash = path.lastIndexOf('/');
			String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
			int q = name.indexOf('?');
			if (q > 0) name = name.substring(0, q);
			return name.isEmpty() ? null : name;
		} catch (Exception e) { return null; }
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
}
