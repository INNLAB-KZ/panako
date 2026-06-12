package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.QueryResultHandler;
import be.panako.strategy.Strategy;
import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.strategy.panako.storage.PanakoStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.FileUtils;
import be.panako.util.Key;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.XAddParams;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kafka consumer/producer for async store and monitor operations.
 *
 * <p>Worker modes (selected via {@code PANAKO_WORKER_MODE} env var / system property):</p>
 * <ul>
 *   <li>{@code stage1} (default) — subscribes to {@link Key#KAFKA_REQUEST_TOPIC}
 *       (default {@code panako.monitor.request}) and publishes to
 *       {@link Key#KAFKA_RESPONSE_TOPIC} (default {@code panako.monitor.response}).
 *       Also handles store topics when {@code KAFKA_MODE=ALL|STORE}.</li>
 *   <li>{@code refine} — dedicated pool for Stage 3 refine requests. Subscribes
 *       only to {@code panako.monitor.refine.request} and publishes to
 *       {@code panako.monitor.refine.response}. Uses consumer group
 *       {@code panako-worker-refine} so it does not compete with stage1 workers.
 *       Store topics are ignored in this mode.</li>
 * </ul>
 *
 * <p>Request/response schemas are identical in both modes — only the topic names
 * and consumer group differ.</p>
 *
 * <p>Consumes from:</p>
 * <ul>
 *   <li>{@code panako-store-requests} — store audio by URL (stage1 only)</li>
 *   <li>{@code panako.monitor.request} or {@code panako.monitor.refine.request} — monitor audio by URL</li>
 * </ul>
 *
 * <p>Produces to:</p>
 * <ul>
 *   <li>{@code panako-store-results} — store results (stage1 only)</li>
 *   <li>{@code panako.monitor.response} or {@code panako.monitor.refine.response} — monitor results</li>
 * </ul>
 *
 * <p>Request format (JSON):</p>
 * <pre>{@code {"audio_url": "https://...", "filename": "ISRC.mp3", "request_id": "optional-correlation-id"}}</pre>
 */
public class PanakoKafkaWorker implements Runnable {

	private static final Logger LOG = Logger.getLogger(PanakoKafkaWorker.class.getName());

	/** Worker modes — see class-level javadoc. */
	public static final String MODE_STAGE1 = "stage1";
	public static final String MODE_REFINE = "refine";
	/** Pseudo-mode handled at launcher level: spawn both stage1 and refine pools
	 *  inside the same JVM. A single {@link PanakoKafkaWorker} instance is always
	 *  one concrete mode — never "both". */
	public static final String MODE_BOTH = "both";

	/** Dedicated topics/group for refine mode. Kept as constants (not Key entries)
	 *  so we don't need to touch upstream {@code be.panako.util.Key}. */
	private static final String REFINE_REQUEST_TOPIC = "panako.monitor.refine.request";
	private static final String REFINE_RESPONSE_TOPIC = "panako.monitor.refine.response";
	private static final String REFINE_GROUP_ID = "panako-worker-refine";

	private final Strategy strategy;
	private final ReentrantLock writeLock;
	private final long maxBytes;
	private final KafkaConsumer<String, String> consumer;
	private final KafkaProducer<String, String> producer;
	private final String storeRequestTopic;
	private final String storeResultTopic;
	private final String monitorRequestTopic;
	private final String monitorResultTopic;
	private final String workerMode;
	/** When true, results are XADDed to Redis Streams (topic name == stream name) instead of
	 *  produced to Kafka. Used during the Kafka→Redis migration: consumes remaining Kafka
	 *  backlog but routes responses to Redis so the orchestrator (Redis-only) sees them. */
	private final boolean resultsToRedis;
	/** Non-null only when {@link #resultsToRedis} is true. */
	private final JedisPool redisPool;
	private volatile boolean running = true;

	private final MatchValidator validator = new MatchValidator();
	/** match_percentage threshold above which the upload is treated as a duplicate of an already-indexed track. */
	private static final double CONTENT_DUP_MATCH_PERCENTAGE = 99.0;

	/**
	 * Resolve {@code PANAKO_WORKER_MODE} from env var / system property.
	 * Returns a lowercased, validated mode — defaults to {@link #MODE_STAGE1}.
	 * Possible values: {@link #MODE_STAGE1}, {@link #MODE_REFINE}, {@link #MODE_BOTH}.
	 */
	private static String getEnvOrDefault(String name, String defaultValue) {
		String val = System.getProperty(name);
		if (val == null) val = System.getenv(name);
		if (val == null || val.trim().isEmpty()) return defaultValue;
		return val.trim();
	}

	static String resolveWorkerMode() {
		String raw = System.getProperty("PANAKO_WORKER_MODE");
		if (raw == null) raw = System.getenv("PANAKO_WORKER_MODE");
		if (raw == null) return MODE_STAGE1;
		String normalized = raw.trim().toLowerCase();
		if (normalized.isEmpty()) return MODE_STAGE1;
		if (!normalized.equals(MODE_STAGE1) && !normalized.equals(MODE_REFINE) && !normalized.equals(MODE_BOTH)) {
			LOG.warning("Unknown PANAKO_WORKER_MODE='" + raw + "' — falling back to '" + MODE_STAGE1 + "'");
			return MODE_STAGE1;
		}
		return normalized;
	}

	/** Collapse {@link #MODE_BOTH} to a concrete per-instance mode ({@link #MODE_STAGE1})
	 *  for the default constructor path. The launcher is responsible for splitting
	 *  "both" into two separate pools via the explicit-mode constructor. */
	private static String resolveInstanceMode() {
		String mode = resolveWorkerMode();
		if (MODE_BOTH.equals(mode)) {
			LOG.warning("PANAKO_WORKER_MODE='both' used with default constructor — "
					+ "single instance can't be both pools; falling back to '" + MODE_STAGE1 + "'. "
					+ "Launcher should use the explicit-mode constructor to spawn both pools.");
			return MODE_STAGE1;
		}
		return mode;
	}

	/** Default constructor — resolves mode from env var. If global mode is {@link #MODE_BOTH},
	 *  the launcher must use the explicit-mode constructor below to spawn stage1 and refine
	 *  workers separately (this constructor collapses "both" to "stage1"). */
	public PanakoKafkaWorker(Strategy strategy, ReentrantLock writeLock, int maxUploadSizeMB) {
		this(strategy, writeLock, maxUploadSizeMB, resolveInstanceMode());
	}

	/** Explicit-mode constructor — used when the launcher needs to force a specific pool
	 *  (e.g. when global mode is {@link #MODE_BOTH}, launcher spawns one stage1 pool and one
	 *  refine pool, each with its own forced mode). */
	public PanakoKafkaWorker(Strategy strategy, ReentrantLock writeLock, int maxUploadSizeMB, String workerMode) {
		this.strategy = strategy;
		this.writeLock = writeLock;
		this.maxBytes = maxUploadSizeMB * 1024L * 1024L;

		if (!MODE_STAGE1.equals(workerMode) && !MODE_REFINE.equals(workerMode)) {
			throw new IllegalArgumentException("Worker instance mode must be '" + MODE_STAGE1
					+ "' or '" + MODE_REFINE + "', got: " + workerMode);
		}
		this.workerMode = workerMode;

		String bootstrapServers = Config.get(Key.KAFKA_BOOTSTRAP_SERVERS);
		String groupId;

		if (workerMode.equals(MODE_REFINE)) {
			// Refine workers use a dedicated topic pair + consumer group so they
			// don't compete with stage1 workers for messages.
			this.storeRequestTopic = Config.get(Key.KAFKA_STORE_REQUEST_TOPIC);
			this.storeResultTopic = Config.get(Key.KAFKA_STORE_RESULT_TOPIC);
			this.monitorRequestTopic = REFINE_REQUEST_TOPIC;
			this.monitorResultTopic = REFINE_RESPONSE_TOPIC;
			groupId = REFINE_GROUP_ID;
		} else {
			// Stage1 (default) — byte-identical to prior behavior.
			this.storeRequestTopic = Config.get(Key.KAFKA_STORE_REQUEST_TOPIC);
			this.storeResultTopic = Config.get(Key.KAFKA_STORE_RESULT_TOPIC);
			this.monitorRequestTopic = Config.get(Key.KAFKA_REQUEST_TOPIC);
			this.monitorResultTopic = Config.get(Key.KAFKA_RESPONSE_TOPIC);
			groupId = Config.get(Key.KAFKA_GROUP_ID);
		}

		// Consumer config
		Properties consumerProps = new Properties();
		consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
		consumerProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
				getEnvOrDefault("KAFKA_MAX_POLL_INTERVAL_MS", "1800000")); // 30 minutes
		consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
				getEnvOrDefault("KAFKA_SESSION_TIMEOUT_MS", "120000")); // 2 minutes — tolerate long monitor tasks + GC pauses
		consumerProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,
				getEnvOrDefault("KAFKA_HEARTBEAT_INTERVAL_MS", "20000")); // 20s — must be < session.timeout/3
		consumerProps.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
				getEnvOrDefault("KAFKA_PARTITION_ASSIGNMENT_STRATEGY",
						"org.apache.kafka.clients.consumer.CooperativeStickyAssignor"));
		this.consumer = new KafkaConsumer<>(consumerProps);

		// Producer config
		Properties producerProps = new Properties();
		producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		this.producer = new KafkaProducer<>(producerProps);

		// Optional output redirect to Redis Streams — used during Kafka→Redis migration to
		// drain the remaining Kafka backlog while routing all responses to Redis (so the
		// orchestrator, which now only reads Redis, still receives them).
		this.resultsToRedis = Boolean.parseBoolean(getEnvOrDefault("KAFKA_RESULTS_TO_REDIS", "false"));
		if (this.resultsToRedis) {
			String redisHost = getEnvOrDefault("REDIS_HOST", "127.0.0.1");
			int redisPort = Integer.parseInt(getEnvOrDefault("REDIS_PORT", "6379"));
			String redisPassword = getEnvOrDefault("REDIS_PASSWORD", "");
			int redisPoolSize = Integer.parseInt(getEnvOrDefault("REDIS_POOL_SIZE", "4"));
			int redisTimeoutMs = Integer.parseInt(getEnvOrDefault("REDIS_TIMEOUT_MS", "10000"));

			JedisPoolConfig poolConfig = new JedisPoolConfig();
			poolConfig.setMaxTotal(redisPoolSize);
			poolConfig.setMaxIdle(redisPoolSize);
			poolConfig.setMinIdle(1);
			poolConfig.setTestOnBorrow(false);
			poolConfig.setTestWhileIdle(true);

			DefaultJedisClientConfig.Builder clientCfg = DefaultJedisClientConfig.builder()
					.connectionTimeoutMillis(redisTimeoutMs)
					.socketTimeoutMillis(redisTimeoutMs);
			if (!redisPassword.isEmpty()) {
				clientCfg.password(redisPassword);
			}
			this.redisPool = new JedisPool(poolConfig, new HostAndPort(redisHost, redisPort), clientCfg.build());
			LOG.info("Kafka worker: KAFKA_RESULTS_TO_REDIS=true — results will be XADDed to Redis at "
					+ redisHost + ":" + redisPort + " (stream name == topic name)");
		} else {
			this.redisPool = null;
		}
	}

	public void stop() {
		running = false;
		consumer.wakeup();
	}

	@Override
	public void run() {
		// Refine workers are monitor-only by design — ignore KAFKA_MODE and skip store topic.
		String kafkaMode = workerMode.equals(MODE_REFINE) ? "MONITOR" : Config.get(Key.KAFKA_MODE).toUpperCase();
		List<String> topics = new ArrayList<>();
		if (kafkaMode.equals("ALL") || kafkaMode.equals("STORE")) topics.add(storeRequestTopic);
		if (kafkaMode.equals("ALL") || kafkaMode.equals("MONITOR")) topics.add(monitorRequestTopic);
		consumer.subscribe(topics);
		LOG.info("Kafka worker started (worker_mode=" + workerMode + ", kafka_mode=" + kafkaMode
				+ ") — listening on " + topics);

		try {
			while (running) {
				try {
					ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
					for (ConsumerRecord<String, String> record : records) {
						try {
							if (record.topic().equals(storeRequestTopic)) {
								handleStoreRequest(record);
							} else if (record.topic().equals(monitorRequestTopic)) {
								handleMonitorRequest(record);
							}
							// Commit only after successful processing
							safeCommit();
						} catch (Exception e) {
							LOG.log(Level.SEVERE, "Kafka message processing failed", e);
							try {
								sendError(record.topic().equals(storeRequestTopic) ? storeResultTopic : monitorResultTopic,
										record.key(), e.getMessage());
							} catch (Exception sendErr) {
								LOG.log(Level.WARNING, "Failed to send error response — broker may be down", sendErr);
							}
							// Commit even on error to avoid infinite retry of bad messages
							safeCommit();
						}
					}
				} catch (org.apache.kafka.common.errors.WakeupException we) {
					// Triggered by stop() — exit loop only if we're really shutting down
					if (!running) break;
				} catch (Exception loopErr) {
					// Broker down, network drop, group rebalance etc. — DON'T kill the thread.
					// KafkaConsumer reconnects transparently; just back off and retry the next poll.
					LOG.log(Level.WARNING, "Kafka poll loop error — will retry after 5s (worker stays alive)", loopErr);
					try {
						Thread.sleep(5000);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		} finally {
			try { consumer.close(); } catch (Exception ignored) {}
			try { producer.close(); } catch (Exception ignored) {}
			if (redisPool != null) {
				try { redisPool.close(); } catch (Exception ignored) {}
			}
			LOG.info("Kafka worker stopped");
		}
	}

	/**
	 * Commit current offsets without letting Kafka group/broker errors kill the worker thread.
	 * If commit fails (e.g. consumer was kicked out of the group during long processing, or broker
	 * is temporarily unreachable), we log and continue — the message will simply be reprocessed
	 * after the consumer rejoins the group, which is acceptable since downstream handlers are
	 * idempotent (store dedupes via {@code hasResource}, monitor results are keyed by recording_id).
	 */
	private void safeCommit() {
		try {
			consumer.commitSync();
		} catch (org.apache.kafka.clients.consumer.CommitFailedException e) {
			LOG.warning("Commit failed (rebalance or group expel) — message will be reprocessed: " + e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.WARNING, "Commit error — continuing (worker stays alive)", e);
		}
	}

	private void handleStoreRequest(ConsumerRecord<String, String> record) {
		String body = record.value();
		String audioUrl = extractJsonString(body, "audio_url");
		String filename = extractJsonString(body, "filename");
		String title = extractJsonString(body, "title");
		String requestId = extractJsonString(body, "recording_id");
		if (requestId == null) requestId = extractJsonString(body, "request_id");

		if (audioUrl == null || audioUrl.isEmpty()) {
			sendError(storeResultTopic, requestId, "Missing 'audio_url'");
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
			Path tempFile = Files.createTempFile("panako_kafka_dl_", "_" + filename);
			try {
				HttpUtil.downloadWithRetry(audioUrl, tempFile, maxBytes);
			} catch (IOException e) {
				Files.deleteIfExists(tempFile);
				sendError(storeResultTopic, requestId, "Download failed: " + e.getMessage());
				return;
			}

			audioFile = tempFile.resolveSibling(filename);
			Files.move(tempFile, audioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

			String filePath = audioFile.toAbsolutePath().toString();
			int identifier = FileUtils.getIdentifier(filePath);
			String isrc = HttpUtil.extractIsrc(filename);

			// Skip indexing when the downloaded audio is empty.
			if (Files.size(audioFile) == 0) {
				LOG.warning("Empty audio (0 bytes) from " + audioUrl + " — skipping indexing");
				StringBuilder json = new StringBuilder();
				json.append("{\"status\":\"skipped_empty\"");
				json.append(",\"request_id\":\"").append(HttpUtil.escapeJson(requestId != null ? requestId : "")).append("\"");
				json.append(",\"identifier\":").append(identifier);
				json.append(",\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\"");
				json.append(",\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\"");
				json.append(",\"title\":").append(StoreFingerprintsHandler.jsonNullableString(title));
				json.append(",\"audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\"");
				json.append("}");
				send(storeResultTopic, requestId, json.toString());
				return;
			}

			// Check duplicate with integrity check
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
					send(storeResultTopic, requestId, json.toString());
					return;
				}
				writeLock.lock();
				try { strategy.delete(filePath); } finally { writeLock.unlock(); }
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
				json.append("{\"status\":\"already_indexed_match\"");
				json.append(",\"request_id\":\"").append(HttpUtil.escapeJson(requestId != null ? requestId : "")).append("\"");
				json.append(",\"identifier\":").append(contentDup.refIdentifier);
				json.append(",\"isrc\":\"").append(HttpUtil.escapeJson(matchedIsrc)).append("\"");
				json.append(",\"filename\":\"").append(HttpUtil.escapeJson(contentDup.refPath)).append("\"");
				json.append(",\"title\":").append(StoreFingerprintsHandler.jsonNullableString(extras[0]));
				json.append(",\"audio_url\":").append(StoreFingerprintsHandler.jsonNullableString(extras[1]));
				json.append(",\"match_percentage\":").append(String.format("%.1f", contentDup.percentOfSecondsWithMatches));
				json.append(",\"submitted_filename\":\"").append(HttpUtil.escapeJson(filename)).append("\"");
				json.append(",\"submitted_audio_url\":\"").append(HttpUtil.escapeJson(audioUrl)).append("\"");
				json.append(",\"submitted_isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\"");
				json.append("}");
				send(storeResultTopic, requestId, json.toString());
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
			send(storeResultTopic, requestId, json.toString());
			LOG.info("Kafka store completed: " + filename + " in " + processingTimeMs + "ms");

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Kafka store failed for " + audioUrl, e);
			sendError(storeResultTopic, requestId, e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
			}
		}
	}

	private void handleMonitorRequest(ConsumerRecord<String, String> record) {
		String body = record.value();
		String audioUrl = extractJsonString(body, "audio_url");
		String recordingId = extractJsonString(body, "recording_id");

		if (audioUrl == null || audioUrl.isEmpty()) {
			sendError(monitorResultTopic, recordingId, "Missing 'audio_url'");
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
			Path tempFile = Files.createTempFile("panako_kafka_monitor_", "_" + filename);
			try {
				HttpUtil.downloadWithRetry(audioUrl, tempFile, maxBytes);
			} catch (IOException e) {
				Files.deleteIfExists(tempFile);
				sendError(monitorResultTopic, recordingId, "Download failed: " + e.getMessage());
				return;
			}
			audioFile = tempFile;

			long startTime = System.currentTimeMillis();
			String filePath = audioFile.toAbsolutePath().toString();

			double totalDuration = MonitorHandler.getAudioDuration(filePath);
			List<QueryResult> allResults;

			if (segments != null && !segments.isEmpty()) {
				// Segments mode: only process specified time ranges
				allResults = new ArrayList<>();
				for (int[] seg : segments) {
					int segStart = seg[0];
					int segDuration = seg[1] - seg[0];
					if (segDuration <= 0) continue;

					Path chunk = MonitorHandler.extractAudioChunkDouble(filePath, segStart, segDuration);
					try {
						List<QueryResult> segResults = MonitorHandler.monitorWithAbsoluteTimes(strategy, chunk.toAbsolutePath().toString());
						// Offset results back to original file timeline
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
				LOG.info("Kafka monitor (segments mode): " + segments.size() + " segments for " + recordingId);
			} else {
				// Full file mode (backward compatible)
				allResults = MonitorHandler.monitorWithAbsoluteTimes(strategy, filePath);
			}

			long processingTimeMs = System.currentTimeMillis() - startTime;

			// Build response with recording_id and duration_seconds prepended
			String monitorJson = MonitorHandler.buildResponseJson(strategy, allResults, filePath, processingTimeMs);
			// Inject recording_id and duration_seconds into the JSON
			String json = "{\"recording_id\":\"" + HttpUtil.escapeJson(recordingId != null ? recordingId : "") + "\"," +
					"\"duration_seconds\":" + String.format("%.1f", totalDuration) + "," +
					monitorJson.substring(1);

			send(monitorResultTopic, recordingId, json);
			LOG.info("Kafka monitor completed: " + filename + " in " + processingTimeMs + "ms");

		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Kafka monitor failed for " + audioUrl, e);
			sendError(monitorResultTopic, recordingId, e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
			}
		}
	}

	/**
	 * Parse "segments" array from JSON: [{"start_seconds":120,"end_seconds":300}, ...]
	 * Returns null if no segments field or empty array.
	 */
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
		// Split by "},{" to get each segment object
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

	/**
	 * Persist the optional {@code title} and {@code audio_url} extras to the
	 * ClickHouse-backed metadata table for the configured strategy. The upstream
	 * {@link Strategy#store} call has already written the base metadata row;
	 * this issues a second {@code INSERT} with the full column set so
	 * {@code ReplacingMergeTree} replaces it on the next background merge.
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

	private void send(String topic, String key, String value) {
		if (resultsToRedis && redisPool != null) {
			// Migration mode: response stream name in Redis matches the Kafka topic name.
			try (Jedis jedis = redisPool.getResource()) {
				Map<String, String> fields = new HashMap<>();
				fields.put("key", key != null ? key : "");
				fields.put("value", value);
				jedis.xadd(topic, XAddParams.xAddParams().maxLen(100000).approximateTrimming(), fields);
			} catch (Exception e) {
				LOG.log(Level.SEVERE, "Failed to XADD result to Redis stream " + topic
						+ " — falling back to Kafka producer", e);
				producer.send(new ProducerRecord<>(topic, key, value), (metadata, exception) -> {
					if (exception != null) {
						LOG.log(Level.SEVERE, "Kafka fallback also failed for " + topic, exception);
					}
				});
			}
			return;
		}
		producer.send(new ProducerRecord<>(topic, key, value), (metadata, exception) -> {
			if (exception != null) {
				LOG.log(Level.SEVERE, "Failed to send Kafka message to " + topic, exception);
			}
		});
	}

	private void sendError(String topic, String recordingId, String message) {
		String json = "{\"status\":\"error\"" +
				",\"recording_id\":\"" + HttpUtil.escapeJson(recordingId != null ? recordingId : "") + "\"" +
				",\"error\":\"" + HttpUtil.escapeJson(message != null ? message : "unknown") + "\"}";
		send(topic, recordingId, json);
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
