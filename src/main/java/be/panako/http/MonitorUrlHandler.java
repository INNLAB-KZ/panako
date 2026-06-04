package be.panako.http;

import be.panako.strategy.Strategy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/monitor/url — monitors audio downloaded from a URL for multiple matches.
 *
 * <p>Accepts JSON: {@code {"audio_url": "https://example.com/radio.mp3"}}</p>
 */
public class MonitorUrlHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(MonitorUrlHandler.class.getName());

	private final Strategy strategy;
	private final long maxBytes;

	public MonitorUrlHandler(Strategy strategy, int maxUploadSizeMB) {
		this.strategy = strategy;
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

			// Download the audio file
			Path tempFile = Files.createTempFile("panako_monitor_dl_", "_" + filename);
			try {
				downloadFile(audioUrl, tempFile, maxBytes);
			} catch (IOException e) {
				Files.deleteIfExists(tempFile);
				HttpUtil.sendError(exchange, 400, "Failed to download audio: " + e.getMessage());
				return;
			}
			audioFile = tempFile;

			String filePath = audioFile.toAbsolutePath().toString();

			if (!MonitorHandler.tryAcquireMonitorSlot()) {
				HttpUtil.sendError(exchange, 429, "Too many concurrent monitor requests. Try again later.");
				return;
			}
			try {
				long startTime = System.currentTimeMillis();

				java.util.List<be.panako.strategy.QueryResult> allResults =
						MonitorHandler.monitorWithAbsoluteTimes(strategy, filePath);

				// Filter by ISRCs if specified
				String isrcsJson = extractJsonString(body, "isrcs");
				if (isrcsJson != null && !isrcsJson.isEmpty()) {
					java.util.Set<String> filterIsrcs = MonitorHandler.parseIsrcsParam(isrcsJson);
					allResults = MonitorHandler.filterByIsrcs(allResults, filterIsrcs);
				}

				long processingTimeMs = System.currentTimeMillis() - startTime;

				String json = MonitorHandler.buildResponseJson(strategy, allResults, filePath, processingTimeMs);
				HttpUtil.sendJson(exchange, 200, json);
			} finally {
				MonitorHandler.releaseMonitorSlot();
			}

		} catch (IOException e) {
			LOG.log(Level.WARNING, "Monitor URL request failed", e);
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Monitor URL request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		} finally {
			if (audioFile != null) {
				try { Files.deleteIfExists(audioFile); } catch (IOException ignored) {}
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

		try (InputStream in = conn.getInputStream();
			 OutputStream out = Files.newOutputStream(target)) {
			byte[] buf = new byte[8192];
			long total = 0;
			int read;
			while ((read = in.read(buf)) != -1) {
				total += read;
				if (total > maxBytes) {
					throw new IOException("Download exceeds maximum size of " + (maxBytes / (1024 * 1024)) + " MB");
				}
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
