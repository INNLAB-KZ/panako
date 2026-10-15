package be.panako.http;

import be.panako.strategy.Strategy;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * GET /api/v1/stats — returns database statistics as JSON.
 */
public class StatsHandler implements HttpHandler {

	private final Strategy strategy;

	public StatsHandler(Strategy strategy) {
		this.strategy = strategy;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			HttpUtil.sendError(exchange, 405, "Method not allowed");
			return;
		}

		try {
			// Capture stdout from printStorageStatistics
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream capture = new PrintStream(baos);
			PrintStream original = System.out;
			System.setOut(capture);
			try {
				strategy.printStorageStatistics();
			} finally {
				System.setOut(original);
			}
			String statsOutput = baos.toString();

			// Parse key numbers from the output
			long fingerprintCount = parseLong(statsOutput, "fingerprint hashes", "Fingerprints");
			if (fingerprintCount == 0) fingerprintCount = parseLong(statsOutput, "items in databases", 0);
			long audioItemsCount = parseLong(statsOutput, "audio files", "Audio items");
			String strategyName = Config.get(Key.STRATEGY);

			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"status\":\"ok\",");
			json.append("\"fingerprint_count\":").append(fingerprintCount).append(",");
			json.append("\"audio_items_count\":").append(audioItemsCount).append(",");
			json.append("\"strategy\":\"").append(HttpUtil.escapeJson(strategyName)).append("\"");
			json.append("}");

			HttpUtil.sendJson(exchange, 200, json.toString());
		} catch (Exception | Error e) {
			HttpUtil.sendError(exchange, 500, "Failed to retrieve stats: " + e.getMessage());
		}
	}

	private long parseLong(String text, String marker, long defaultValue) {
		for (String line : text.split("\n")) {
			if (line.contains(marker)) {
				String[] parts = line.replaceAll("[^0-9 ]", " ").trim().split("\\s+");
				for (String part : parts) {
					try {
						return Long.parseLong(part);
					} catch (NumberFormatException ignored) {
					}
				}
			}
		}
		return defaultValue;
	}

	private long parseLong(String text, String marker1, String marker2) {
		long val = parseLong(text, marker1, -1);
		if (val >= 0) return val;
		return parseLong(text, marker2, 0);
	}
}
