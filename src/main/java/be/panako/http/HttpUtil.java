package be.panako.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared HTTP utilities for the Panako API handlers.
 */
public final class HttpUtil {

	private static final Logger LOG = Logger.getLogger(HttpUtil.class.getName());

	/** Default download retry attempts (origin server may close TCP early). */
	public static final int DEFAULT_DOWNLOAD_ATTEMPTS = 3;
	/** Backoff sleeps between attempts (ms). Length = max attempts - 1. */
	private static final long[] DOWNLOAD_BACKOFF_MS = {2_000L, 5_000L, 15_000L};

	private HttpUtil() {}

	/**
	 * Download {@code urlString} into {@code target} with automatic retry on
	 * {@link IOException}. The connection is established up to
	 * {@link #DEFAULT_DOWNLOAD_ATTEMPTS} times, with a 2s / 5s backoff between
	 * attempts. The transfer is aborted when more than {@code maxBytes} have
	 * been read. The {@code target} file is truncated at the start of every
	 * attempt so partial state from a failed attempt never leaks through.
	 *
	 * <p>Common reason this retries: the origin HTTP server (CDN, object store)
	 * closes the TCP connection mid-body — the JDK reports it as "peer closed
	 * connection without sending complete message body".</p>
	 */
	public static void downloadWithRetry(String urlString, Path target, long maxBytes) throws IOException {
		IOException last = null;
		for (int attempt = 1; attempt <= DEFAULT_DOWNLOAD_ATTEMPTS; attempt++) {
			try {
				downloadOnce(urlString, target, maxBytes);
				if (attempt > 1) {
					LOG.info("Download succeeded on attempt " + attempt + " for " + urlString);
				}
				return;
			} catch (IOException e) {
				last = e;
				if (attempt >= DEFAULT_DOWNLOAD_ATTEMPTS) break;
				long sleepMs = DOWNLOAD_BACKOFF_MS[Math.min(attempt - 1, DOWNLOAD_BACKOFF_MS.length - 1)];
				LOG.log(Level.WARNING, "Download attempt " + attempt + "/" + DEFAULT_DOWNLOAD_ATTEMPTS
						+ " failed for " + urlString + " — retrying in " + sleepMs + "ms: " + e.getMessage());
				try {
					Thread.sleep(sleepMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting to retry download", ie);
				}
			}
		}
		throw last;
	}

	private static void downloadOnce(String urlString, Path target, long maxBytes) throws IOException {
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

	/**
	 * Send a JSON response.
	 */
	public static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	/**
	 * Send an error JSON response.
	 */
	public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
		String json = "{\"status\":\"error\",\"message\":\"" + escapeJson(message) + "\"}";
		sendJson(exchange, statusCode, json);
	}

	/**
	 * Parse query parameters from the request URI.
	 */
	public static Map<String, String> parseQueryParams(HttpExchange exchange) {
		Map<String, String> params = new LinkedHashMap<>();
		URI uri = exchange.getRequestURI();
		String query = uri.getRawQuery();
		if (query == null || query.isEmpty()) return params;
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				params.put(pair.substring(0, eq), pair.substring(eq + 1));
			}
		}
		return params;
	}

	/**
	 * Extract ISRC from a filename like "USRC17607839.mp3" -> "USRC17607839".
	 * Returns the part before the last dot (the extension).
	 */
	public static String extractIsrc(String filename) {
		if (filename == null || filename.isEmpty()) return null;
		// Strip path if present
		int sep = filename.lastIndexOf('/');
		if (sep >= 0) filename = filename.substring(sep + 1);
		sep = filename.lastIndexOf('\\');
		if (sep >= 0) filename = filename.substring(sep + 1);
		// Remove extension
		int dot = filename.lastIndexOf('.');
		if (dot > 0) return filename.substring(0, dot);
		return filename;
	}

	/**
	 * Parse the metadata string from Strategy.metadata() and extract duration and fingerprint count.
	 * Format: "identifier ; path ; duration (s) ; numFingerprints (#) ; printsPerSecond (#/s)"
	 * Returns [duration, fingerprintCount] or null if parsing fails.
	 */
	public static double[] parseMetadata(String metadata) {
		if (metadata == null || metadata.isEmpty()) return null;
		try {
			String[] parts = metadata.split(";");
			if (parts.length >= 4) {
				double duration = Double.parseDouble(parts[2].replaceAll("[^0-9.]", "").trim());
				int fingerprints = Integer.parseInt(parts[3].replaceAll("[^0-9]", "").trim());
				return new double[]{duration, fingerprints};
			}
		} catch (NumberFormatException ignored) {}
		return null;
	}

	/**
	 * Minimal JSON string escaping.
	 */
	public static String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}
