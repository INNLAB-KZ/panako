package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.Strategy;
import be.panako.util.Config;
import be.panako.util.FileUtils;
import be.panako.util.Key;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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
 * <p>Consumes from:</p>
 * <ul>
 *   <li>{@code panako-store-requests} — store audio by URL</li>
 *   <li>{@code panako-monitor-requests} — monitor audio by URL</li>
 * </ul>
 *
 * <p>Produces to:</p>
 * <ul>
 *   <li>{@code panako-store-results} — store results</li>
 *   <li>{@code panako-monitor-results} — monitor results</li>
 * </ul>
 *
 * <p>Request format (JSON):</p>
 * <pre>{@code {"audio_url": "https://...", "filename": "ISRC.mp3", "request_id": "optional-correlation-id"}}</pre>
 */
public class PanakoKafkaWorker implements Runnable {

	private static final Logger LOG = Logger.getLogger(PanakoKafkaWorker.class.getName());

	private final Strategy strategy;
	private final ReentrantLock writeLock;
	private final long maxBytes;
	private final KafkaConsumer<String, String> consumer;
	private final KafkaProducer<String, String> producer;
	private final String storeRequestTopic;
	private final String storeResultTopic;
	private final String monitorRequestTopic;
	private final String monitorResultTopic;
	private volatile boolean running = true;

	public PanakoKafkaWorker(Strategy strategy, ReentrantLock writeLock, int maxUploadSizeMB) {
		this.strategy = strategy;
		this.writeLock = writeLock;
		this.maxBytes = maxUploadSizeMB * 1024L * 1024L;

		String bootstrapServers = Config.get(Key.KAFKA_BOOTSTRAP_SERVERS);
		String groupId = Config.get(Key.KAFKA_GROUP_ID);

		this.storeRequestTopic = Config.get(Key.KAFKA_STORE_REQUEST_TOPIC);
		this.storeResultTopic = Config.get(Key.KAFKA_STORE_RESULT_TOPIC);
		this.monitorRequestTopic = Config.get(Key.KAFKA_REQUEST_TOPIC);
		this.monitorResultTopic = Config.get(Key.KAFKA_RESPONSE_TOPIC);

		// Consumer config
		Properties consumerProps = new Properties();
		consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
		consumerProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "600000"); // 10 minutes
		consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
		this.consumer = new KafkaConsumer<>(consumerProps);

		// Producer config
		Properties producerProps = new Properties();
		producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		this.producer = new KafkaProducer<>(producerProps);
	}

	public void stop() {
		running = false;
		consumer.wakeup();
	}

	@Override
	public void run() {
		String mode = Config.get(Key.KAFKA_MODE).toUpperCase();
		List<String> topics = new ArrayList<>();
		if (mode.equals("ALL") || mode.equals("STORE")) topics.add(storeRequestTopic);
		if (mode.equals("ALL") || mode.equals("MONITOR")) topics.add(monitorRequestTopic);
		consumer.subscribe(topics);
		LOG.info("Kafka worker started (mode=" + mode + ") — listening on " + topics);

		try {
			while (running) {
				ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
				for (ConsumerRecord<String, String> record : records) {
					try {
						if (record.topic().equals(storeRequestTopic)) {
							handleStoreRequest(record);
						} else if (record.topic().equals(monitorRequestTopic)) {
							handleMonitorRequest(record);
						}
						// Commit only after successful processing
						consumer.commitSync();
					} catch (Exception e) {
						LOG.log(Level.SEVERE, "Kafka message processing failed", e);
						sendError(record.topic().equals(storeRequestTopic) ? storeResultTopic : monitorResultTopic,
								record.key(), e.getMessage());
						// Commit even on error to avoid infinite retry of bad messages
						consumer.commitSync();
					}
				}
			}
		} finally {
			consumer.close();
			producer.close();
			LOG.info("Kafka worker stopped");
		}
	}

	private void handleStoreRequest(ConsumerRecord<String, String> record) {
		String body = record.value();
		String audioUrl = extractJsonString(body, "audio_url");
		String filename = extractJsonString(body, "filename");
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
				downloadFile(audioUrl, tempFile, maxBytes);
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
					json.append(",\"duration_seconds\":").append(String.format("%.1f", meta[0]));
					json.append(",\"fingerprints_count\":").append((int) meta[1]);
					json.append("}");
					send(storeResultTopic, requestId, json.toString());
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

			StringBuilder json = new StringBuilder();
			json.append("{\"status\":\"ok\"");
			json.append(",\"request_id\":\"").append(HttpUtil.escapeJson(requestId != null ? requestId : "")).append("\"");
			json.append(",\"identifier\":").append(identifier);
			json.append(",\"isrc\":\"").append(HttpUtil.escapeJson(isrc)).append("\"");
			json.append(",\"filename\":\"").append(HttpUtil.escapeJson(filename)).append("\"");
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

		Path audioFile = null;
		try {
			Path tempFile = Files.createTempFile("panako_kafka_monitor_", "_" + filename);
			try {
				downloadFile(audioUrl, tempFile, maxBytes);
			} catch (IOException e) {
				Files.deleteIfExists(tempFile);
				sendError(monitorResultTopic, recordingId, "Download failed: " + e.getMessage());
				return;
			}
			audioFile = tempFile;

			long startTime = System.currentTimeMillis();
			String filePath = audioFile.toAbsolutePath().toString();

			double totalDuration = MonitorHandler.getAudioDuration(filePath);
			List<QueryResult> allResults = MonitorHandler.monitorWithAbsoluteTimes(strategy, filePath);
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

	private void send(String topic, String key, String value) {
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
