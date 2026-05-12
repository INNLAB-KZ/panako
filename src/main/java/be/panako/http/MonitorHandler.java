package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.QueryResultHandler;
import be.panako.strategy.Strategy;
import be.panako.strategy.olaf.OlafStrategy;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/v1/monitor — monitors a long audio file and returns all matches found.
 *
 * <p>Unlike {@link QueryHandler} which expects a single match, this handler splits the audio
 * into overlapping windows (using ffmpeg) and queries each window separately, adjusting
 * query times to be absolute (relative to the full recording).
 * Results are then deduplicated by track identifier and refined for precise start/end times.</p>
 *
 * <p>Accepts multipart/form-data with an "audio" field containing the audio file.</p>
 */
public class MonitorHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(MonitorHandler.class.getName());

	/** Minimum gap (seconds) in match_start/match_end to trigger refinement */
	// All thresholds read from config at call time — no hardcoded constants

	private static final Semaphore monitorSemaphore =
			new Semaphore(Config.getInt(Key.MONITOR_MAX_CONCURRENT));

	static boolean tryAcquireMonitorSlot() {
		return monitorSemaphore.tryAcquire();
	}

	static void releaseMonitorSlot() {
		monitorSemaphore.release();
	}

	private final Strategy strategy;
	private final long maxBytes;

	public MonitorHandler(Strategy strategy, int maxUploadSizeMB) {
		this.strategy = strategy;
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
		try {
			upload = MultipartParser.parse(exchange.getRequestBody(), contentType, maxBytes);
			if (upload == null) {
				HttpUtil.sendError(exchange, 400, "No audio file found in request (field name: audio)");
				return;
			}

			String filePath = upload.tempFile.toAbsolutePath().toString();

			// Parse optional query params for step_size and overlap
			Map<String, String> params = HttpUtil.parseQueryParams(exchange);
			int stepSize = params.containsKey("step_size")
					? Integer.parseInt(params.get("step_size"))
					: Config.getInt(Key.MONITOR_STEP_SIZE);
			int overlap = params.containsKey("overlap")
					? Integer.parseInt(params.get("overlap"))
					: Config.getInt(Key.MONITOR_OVERLAP);

			if (!monitorSemaphore.tryAcquire()) {
				HttpUtil.sendError(exchange, 429,
						"Too many concurrent monitor requests (max " +
						Config.getInt(Key.MONITOR_MAX_CONCURRENT) + "). Try again later.");
				return;
			}
			try {
				long startTime = System.currentTimeMillis();

				List<QueryResult> allResults = monitorWithAbsoluteTimes(strategy, filePath, stepSize, overlap);

				// Filter by ISRCs if specified
				String isrcsParam = params.get("isrcs");
				if (isrcsParam != null && !isrcsParam.isEmpty()) {
					Set<String> filterIsrcs = parseIsrcsParam(isrcsParam);
					allResults = filterByIsrcs(allResults, filterIsrcs);
				}

				long processingTimeMs = System.currentTimeMillis() - startTime;

				String json = buildResponseJson(strategy, allResults, filePath, processingTimeMs);
				HttpUtil.sendJson(exchange, 200, json);
			} finally {
				monitorSemaphore.release();
			}

		} catch (IOException e) {
			LOG.log(Level.WARNING, "Monitor request failed", e);
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Monitor request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		} finally {
			if (upload != null) {
				try { Files.deleteIfExists(upload.tempFile); } catch (IOException ignored) {}
			}
		}
	}

	/**
	 * Splits audio into overlapping windows using ffmpeg, queries each window,
	 * and adjusts queryStart/queryStop to be absolute times in the full recording.
	 */
	static List<QueryResult> monitorWithAbsoluteTimes(Strategy strategy, String filePath) throws IOException {
		return monitorWithAbsoluteTimes(strategy, filePath,
				Config.getInt(Key.MONITOR_STEP_SIZE), Config.getInt(Key.MONITOR_OVERLAP));
	}

	static List<QueryResult> monitorWithAbsoluteTimes(Strategy strategy, String filePath,
													  int stepSize, int overlap) throws IOException {
		double totalDuration = getAudioDuration(filePath);
		int actualStep = stepSize - overlap;
		int maxResults = Config.getInt(Key.NUMBER_OF_QUERY_RESULTS);
		int parallelism = Config.getInt(Key.MONITOR_PARALLEL_WINDOWS);

		// Pass 1: coarse scan
		List<QueryResult> pass1Results;
		if (parallelism <= 1) {
			pass1Results = monitorSequential(strategy, filePath, stepSize, actualStep, maxResults, totalDuration);
		} else {
			pass1Results = monitorParallel(strategy, filePath, stepSize, actualStep, maxResults, totalDuration, parallelism);
		}

		// Find gaps — time ranges not covered by any match
		// Skip pass 2 if pass 1 found nothing (no point scanning entire file again)
		List<double[]> gaps = pass1Results.isEmpty()
				? Collections.emptyList()
				: findGaps(pass1Results, totalDuration, stepSize);
		if (gaps.isEmpty()) {
			return pass1Results;
		}

		// Pass 2: fine scan on gaps only
		int fineStep = Config.getInt(Key.MONITOR_STEP_SIZE_FINE);
		int fineOverlap = Config.getInt(Key.MONITOR_OVERLAP_FINE);
		int fineActualStep = fineStep - fineOverlap;

		List<QueryResult> pass2Results = new ArrayList<>();
		for (double[] gap : gaps) {
			double gapStart = gap[0];
			double gapEnd = gap[1];
			double gapDuration = gapEnd - gapStart;
			if (gapDuration < fineStep) continue;

			// Extract gap region and scan with fine windows
			for (double t = gapStart; t + fineStep <= gapEnd; t += fineActualStep) {
				Path chunk = extractAudioChunkDouble(filePath, t, fineStep);
				try {
					CollectingResultHandler handler = new CollectingResultHandler();
					strategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

					for (QueryResult r : handler.results) {
						pass2Results.add(new QueryResult(
								r.queryPath, r.queryStart + t, r.queryStop + t,
								r.refPath, r.refIdentifier, r.refStart, r.refStop,
								r.score, r.timeFactor, r.frequencyFactor,
								r.percentOfSecondsWithMatches));
					}
				} finally {
					try { Files.deleteIfExists(chunk); } catch (IOException ignored) {}
				}
			}
		}

		// Merge pass1 + pass2, deduplicate by identifier + time overlap
		List<QueryResult> allResults = new ArrayList<>(pass1Results);
		for (QueryResult r2 : pass2Results) {
			boolean duplicate = false;
			for (QueryResult r1 : pass1Results) {
				if (r1.refIdentifier.equals(r2.refIdentifier)
						&& r2.queryStart >= r1.queryStart - 5
						&& r2.queryStop <= r1.queryStop + 5) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) {
				allResults.add(r2);
			}
		}

		return allResults;
	}

	/**
	 * Find time gaps not covered by any query result.
	 * Returns list of [gapStart, gapEnd] pairs.
	 */
	private static List<double[]> findGaps(List<QueryResult> results, double totalDuration, int stepSize) {
		if (results.isEmpty()) {
			return List.of(new double[]{0, totalDuration});
		}

		// Build covered intervals from results
		List<double[]> covered = new ArrayList<>();
		for (QueryResult r : results) {
			covered.add(new double[]{r.queryStart, r.queryStop});
		}
		covered.sort(Comparator.comparingDouble(a -> a[0]));

		// Merge overlapping intervals
		List<double[]> merged = new ArrayList<>();
		double[] cur = covered.get(0);
		for (int i = 1; i < covered.size(); i++) {
			if (covered.get(i)[0] <= cur[1]) {
				cur[1] = Math.max(cur[1], covered.get(i)[1]);
			} else {
				merged.add(cur);
				cur = covered.get(i);
			}
		}
		merged.add(cur);

		// Find gaps between merged intervals
		List<double[]> gaps = new ArrayList<>();
		if (merged.get(0)[0] > 0) {
			gaps.add(new double[]{0, merged.get(0)[0]});
		}
		for (int i = 1; i < merged.size(); i++) {
			double gapStart = merged.get(i - 1)[1];
			double gapEnd = merged.get(i)[0];
			if (gapEnd - gapStart > 0) {
				gaps.add(new double[]{gapStart, gapEnd});
			}
		}
		if (merged.get(merged.size() - 1)[1] < totalDuration) {
			gaps.add(new double[]{merged.get(merged.size() - 1)[1], totalDuration});
		}

		return gaps;
	}

	private static List<QueryResult> monitorSequential(Strategy strategy, String filePath,
			int stepSize, int actualStep, int maxResults, double totalDuration) throws IOException {
		List<QueryResult> allResults = new ArrayList<>();
		// Defense-in-depth: even if totalDuration is wrong, stop as soon as ffmpeg
		// returns a near-empty chunk (i.e. we walked past real EOF).
		long minChunkBytes = minChunkBytesForStep(1);

		for (int t = 0; t + stepSize < totalDuration; t += actualStep) {
			Path chunk = extractAudioChunk(filePath, t, stepSize);
			try {
				long sz = Files.size(chunk);
				if (sz < minChunkBytes) {
					LOG.info("EOF detected at t=" + t + "s (chunk=" + sz + "B) — stopping monitor loop");
					break;
				}
				CollectingResultHandler handler = new CollectingResultHandler();
				strategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

				for (QueryResult r : handler.results) {
					allResults.add(new QueryResult(
							r.queryPath, r.queryStart + t, r.queryStop + t,
							r.refPath, r.refIdentifier, r.refStart, r.refStop,
							r.score, r.timeFactor, r.frequencyFactor,
							r.percentOfSecondsWithMatches));
				}
			} finally {
				try { Files.deleteIfExists(chunk); } catch (IOException ignored) {}
			}
		}

		return allResults;
	}

	/**
	 * Minimum byte size for a chunk that contains at least {@code seconds} of real
	 * audio. Chunks smaller than this are treated as past-EOF artefacts from ffmpeg
	 * (typically a 44-byte WAV header with no samples).
	 */
	private static long minChunkBytesForStep(int seconds) {
		int sampleRate = Config.getInt(Key.OLAF_SAMPLE_RATE);
		// 44-byte WAV header + mono 16-bit PCM samples.
		return 44L + (long) sampleRate * 2L * seconds;
	}

	private static List<QueryResult> monitorParallel(Strategy strategy, String filePath,
			int stepSize, int actualStep, int maxResults, double totalDuration, int parallelism) throws IOException {
		List<Integer> offsets = new ArrayList<>();
		for (int t = 0; t + stepSize < totalDuration; t += actualStep) {
			offsets.add(t);
		}

		List<QueryResult> allResults = Collections.synchronizedList(new ArrayList<>());
		ExecutorService executor = Executors.newFixedThreadPool(parallelism);

		// Defense-in-depth: if a chunk comes back near-empty we treat that as past-EOF
		// and signal all in-flight / pending tasks to bail out. Without this, a wildly
		// inflated container duration would still cost us thousands of useless ffmpeg
		// extractions before the loop terminates naturally.
		final long minChunkBytes = minChunkBytesForStep(1);
		final java.util.concurrent.atomic.AtomicBoolean eofReached =
				new java.util.concurrent.atomic.AtomicBoolean(false);

		List<Future<?>> futures = new ArrayList<>();
		for (int t : offsets) {
			final int offset = t;
			futures.add(executor.submit(() -> {
				if (eofReached.get()) return;
				Path chunk = null;
				try {
					chunk = extractAudioChunk(filePath, offset, stepSize);
					long sz = Files.size(chunk);
					if (sz < minChunkBytes) {
						if (eofReached.compareAndSet(false, true)) {
							LOG.info("EOF detected at t=" + offset + "s (chunk=" + sz + "B) — short-circuiting remaining windows");
						}
						return;
					}
					Strategy localStrategy = new OlafStrategy();
					CollectingResultHandler handler = new CollectingResultHandler();
					localStrategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

					for (QueryResult r : handler.results) {
						allResults.add(new QueryResult(
								r.queryPath, r.queryStart + offset, r.queryStop + offset,
								r.refPath, r.refIdentifier, r.refStart, r.refStop,
								r.score, r.timeFactor, r.frequencyFactor,
								r.percentOfSecondsWithMatches));
					}
				} catch (IOException e) {
					LOG.log(Level.WARNING, "Failed to process window at offset " + offset, e);
				} finally {
					if (chunk != null) {
						try { Files.deleteIfExists(chunk); } catch (IOException ignored) {}
					}
				}
			}));
		}

		for (Future<?> f : futures) {
			try {
				f.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (ExecutionException e) {
				LOG.log(Level.WARNING, "Window processing failed", e.getCause());
			}
		}
		executor.shutdown();

		return allResults;
	}

	/**
	 * Get audio duration in seconds.
	 *
	 * <p>For m4a/aac/mp4 containers (and whenever ffprobe reports an implausible value)
	 * we fall back to a full ffmpeg decode-to-null and parse the final {@code time=}
	 * line from stderr. AAC/M4A files in the wild routinely carry broken container
	 * headers (e.g. {@code duration=30:00:00} for a 01:01:39 stream); using that bad
	 * value drove the monitor loop to extract ~30x extra empty windows.</p>
	 */
	static double getAudioDuration(String filePath) throws IOException {
		double fast = ffprobeFormatDuration(filePath);
		String lower = filePath.toLowerCase();
		boolean risky = lower.endsWith(".m4a") || lower.endsWith(".aac")
				|| lower.endsWith(".mp4") || lower.endsWith(".m4b");
		if (risky || Double.isNaN(fast) || fast <= 0 || fast > 24 * 3600) {
			double real = ffmpegDecodeDuration(filePath);
			if (real > 0) {
				if (!Double.isNaN(fast) && fast > 0 && Math.abs(real - fast) > 5) {
					LOG.warning("Container duration lied: header=" + fast
							+ "s, real=" + real + "s for " + filePath);
				}
				return real;
			}
		}
		if (Double.isNaN(fast) || fast <= 0) {
			throw new IOException("Could not determine audio duration for " + filePath);
		}
		return fast;
	}

	/** Reads container-level duration. Returns NaN on any failure. */
	private static double ffprobeFormatDuration(String filePath) {
		try {
			ProcessBuilder pb = new ProcessBuilder(
					"ffprobe", "-v", "error",
					"-show_entries", "format=duration",
					"-of", "default=noprint_wrappers=1:nokey=1",
					filePath);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String output = new String(p.getInputStream().readAllBytes()).trim();
			p.waitFor();
			return Double.parseDouble(output);
		} catch (Exception e) {
			return Double.NaN;
		}
	}

	/**
	 * Decodes the audio stream to null and returns the actual elapsed time reported
	 * by ffmpeg. Slow but truthful — needed when container header lies about duration.
	 * Returns -1 if no usable {@code time=} line was emitted.
	 */
	private static double ffmpegDecodeDuration(String filePath) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(
				"ffmpeg", "-nostdin", "-i", filePath,
				"-vn", "-map", "0:a:0", "-f", "null", "-");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String log = new String(p.getInputStream().readAllBytes());
		try {
			p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(log);
		double last = -1;
		while (m.find()) {
			last = Integer.parseInt(m.group(1)) * 3600.0
					+ Integer.parseInt(m.group(2)) * 60.0
					+ Double.parseDouble(m.group(3));
		}
		return last;
	}

	/**
	 * Extract an audio chunk using ffmpeg.
	 */
	static Path extractAudioChunk(String filePath, int offsetSec, int durationSec) throws IOException {
		return extractAudioChunkDouble(filePath, offsetSec, durationSec);
	}

	/**
	 * Extract an audio chunk using ffmpeg with double-precision offset.
	 */
	static Path extractAudioChunkDouble(String filePath, double offsetSec, int durationSec) throws IOException {
		int sampleRate = Config.getInt(Key.OLAF_SAMPLE_RATE);
		Path chunk = Files.createTempFile("panako_chunk_", ".wav");
		ProcessBuilder pb = new ProcessBuilder(
				"ffmpeg", "-y",
				"-ss", String.format("%.2f", offsetSec),
				"-t", String.valueOf(durationSec),
				"-i", filePath,
				"-ar", String.valueOf(sampleRate),
				"-ac", "1",
				chunk.toAbsolutePath().toString());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		try {
			p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (p.exitValue() != 0) {
			Files.deleteIfExists(chunk);
			throw new IOException("ffmpeg chunk extraction failed for offset " + offsetSec);
		}
		return chunk;
	}

	/**
	 * Extracts waveform data (peak amplitudes) from an audio file using ffmpeg.
	 * Returns one float per second, normalized to 0.0-1.0.
	 */
	static float[] extractWaveform(String filePath) throws IOException {
		double duration = getAudioDuration(filePath);
		int totalSeconds = (int) Math.ceil(duration);
		if (totalSeconds <= 0) return new float[0];

		// Decode to raw 16-bit mono PCM at 8000 Hz (low rate for speed)
		int sampleRate = 8000;
		ProcessBuilder pb = new ProcessBuilder(
				"ffmpeg", "-y", "-i", filePath,
				"-ac", "1", "-ar", String.valueOf(sampleRate),
				"-f", "s16le", "-acodec", "pcm_s16le", "pipe:1");
		pb.redirectErrorStream(false);
		Process p = pb.start();
		Thread errThread = new Thread(() -> {
			try { p.getErrorStream().readAllBytes(); } catch (IOException ignored) {}
		});
		errThread.setDaemon(true);
		errThread.start();

		byte[] raw = p.getInputStream().readAllBytes();
		try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

		int totalSamples = raw.length / 2;
		float[] waveform = new float[totalSeconds];

		for (int sec = 0; sec < totalSeconds; sec++) {
			int startSample = sec * sampleRate;
			int endSample = Math.min(startSample + sampleRate, totalSamples);
			float peak = 0;
			for (int i = startSample; i < endSample; i++) {
				if (i * 2 + 1 >= raw.length) break;
				int lo = raw[i * 2] & 0xFF;
				int hi = raw[i * 2 + 1];
				short sample = (short) (lo | (hi << 8));
				float abs = Math.abs(sample) / 32768.0f;
				if (abs > peak) peak = abs;
			}
			waveform[sec] = peak;
		}

		return waveform;
	}

	static String buildResponseJson(Strategy strategy, List<QueryResult> results,
									String recordingPath, long processingTimeMs) {
		// Group by identifier
		Map<String, List<QueryResult>> byId = new LinkedHashMap<>();
		for (QueryResult r : results) {
			byId.computeIfAbsent(r.refIdentifier, k -> new ArrayList<>()).add(r);
		}

		double maxGap = Config.getFloat(Key.MONITOR_WINDOW_GAP_THRESHOLD);

		// Merge each group, splitting into clusters by gap threshold
		List<MergedMatch> merged = new ArrayList<>();
		for (Map.Entry<String, List<QueryResult>> entry : byId.entrySet()) {
			List<QueryResult> group = entry.getValue();
			group.sort(Comparator.comparingDouble(r -> r.queryStart));

			QueryResult first = group.get(0);
			MergedMatch m = new MergedMatch();
			m.identifier = first.refIdentifier;
			m.isrc = HttpUtil.extractIsrc(first.refPath);
			m.filename = first.refPath;

			// Resolve effective gap threshold: cap by half of the track duration
			// when known, so very short tracks cannot legitimately span huge gaps.
			double trackDuration = -1;
			if (strategy != null) {
				try {
					String metadata = strategy.metadata(m.filename);
					double[] parsed = HttpUtil.parseMetadata(metadata);
					if (parsed != null) trackDuration = parsed[0];
				} catch (Exception e) {
					LOG.log(Level.FINE, "Could not get metadata for " + m.filename, e);
				}
			}
			double gapThreshold = effectiveGapThreshold(maxGap, trackDuration);

			List<List<QueryResult>> clusters = clusterWindows(group, gapThreshold);
			for (List<QueryResult> cluster : clusters) {
				m.windows.add(buildWindow(cluster));
			}
			recomputeEnvelope(m, group);
			merged.add(m);
		}

		// Refinement pass — refine each cluster individually so refinement
		// cannot stitch two separate occurrences across the gap that put them
		// in different clusters.
		if (recordingPath != null && strategy != null) {
			for (MergedMatch m : merged) {
				double trackDuration = -1;
				try {
					String metadata = strategy.metadata(m.filename);
					double[] parsed = HttpUtil.parseMetadata(metadata);
					if (parsed != null) trackDuration = parsed[0];
				} catch (Exception e) {
					LOG.log(Level.FINE, "Could not get metadata for " + m.filename, e);
				}
				double gapThreshold = effectiveGapThreshold(maxGap, trackDuration);
				for (MatchWindow w : m.windows) {
					try {
						refineWindowBoundaries(strategy, recordingPath, m.identifier,
								m.filename, w, trackDuration, gapThreshold);
					} catch (Exception e) {
						LOG.log(Level.WARNING, "Refinement failed for " + m.isrc + ": " + e.getMessage());
					}
				}
				// Envelope must reflect refined window boundaries.
				recomputeEnvelopeFromWindows(m);
			}
		}

		// Filter out false positives: low score or low match percentage
		int minScore = Config.getInt(Key.MONITOR_MIN_SCORE);
		double minPct = Config.getFloat(Key.MONITOR_MIN_PERCENTAGE);
		merged.removeIf(mm -> mm.score < minScore || mm.matchPercentage < minPct);

		// Sort by query start time
		merged.sort(Comparator.comparingDouble(mm -> mm.queryStart));

		StringBuilder json = new StringBuilder();
		json.append("{");
		json.append("\"status\":\"ok\",");
		json.append("\"processing_time_ms\":").append(processingTimeMs).append(",");
		json.append("\"unique_tracks_count\":").append(merged.size()).append(",");
		json.append("\"matches\":[");

		for (int i = 0; i < merged.size(); i++) {
			MergedMatch m = merged.get(i);
			if (i > 0) json.append(",");
			json.append("{");
			json.append("\"identifier\":").append(m.identifier).append(",");
			json.append("\"isrc\":\"").append(HttpUtil.escapeJson(m.isrc)).append("\",");
			json.append("\"filename\":\"").append(HttpUtil.escapeJson(m.filename)).append("\",");
			json.append("\"query_start_seconds\":").append(String.format("%.1f", m.queryStart)).append(",");
			json.append("\"query_start_time\":\"").append(formatTime(m.queryStart)).append("\",");
			json.append("\"query_end_seconds\":").append(String.format("%.1f", m.queryEnd)).append(",");
			json.append("\"query_end_time\":\"").append(formatTime(m.queryEnd)).append("\",");
			json.append("\"match_start_seconds\":").append(String.format("%.1f", m.refStart)).append(",");
			json.append("\"match_end_seconds\":").append(String.format("%.1f", m.refEnd)).append(",");
			json.append("\"score\":").append(m.score).append(",");
			json.append("\"time_factor\":").append(String.format("%.3f", m.timeFactor)).append(",");
			json.append("\"frequency_factor\":").append(String.format("%.3f", m.frequencyFactor)).append(",");
			json.append("\"match_percentage\":").append(String.format("%.1f", m.matchPercentage)).append(",");
			json.append("\"window_hits\":").append(m.windowHits).append(",");
			// Per-occurrence breakdown. One entry per contiguous play of the
			// track — if the same track plays twice on a recording with a gap,
			// there will be two windows here while the envelope fields above
			// stay min/max across both occurrences.
			json.append("\"windows\":[");
			for (int wi = 0; wi < m.windows.size(); wi++) {
				MatchWindow w = m.windows.get(wi);
				if (wi > 0) json.append(",");
				json.append("{");
				json.append("\"query_start_seconds\":").append(String.format("%.1f", w.queryStart)).append(",");
				json.append("\"query_end_seconds\":").append(String.format("%.1f", w.queryEnd)).append(",");
				json.append("\"match_start_seconds\":").append(String.format("%.1f", w.refStart)).append(",");
				json.append("\"match_end_seconds\":").append(String.format("%.1f", w.refEnd)).append(",");
				json.append("\"score\":").append(w.score).append(",");
				json.append("\"window_hits\":").append(w.windowHits);
				json.append("}");
			}
			json.append("]");
			json.append("}");
		}

		json.append("],");

		// Waveform — peak amplitude per second for the full recording
		json.append("\"waveform\":[");
		if (recordingPath != null) {
			try {
				float[] waveform = extractWaveform(recordingPath);
				for (int i = 0; i < waveform.length; i++) {
					if (i > 0) json.append(",");
					json.append(String.format("%.3f", waveform[i]));
				}
			} catch (Exception e) {
				LOG.log(Level.WARNING, "Waveform extraction failed: " + e.getMessage());
			}
		}
		json.append("]");

		json.append("}");
		return json.toString();
	}

	/**
	 * Formats seconds as HH:mm:ss.
	 */
	private static String formatTime(double totalSeconds) {
		int total = (int) totalSeconds;
		int hours = total / 3600;
		int minutes = (total % 3600) / 60;
		int seconds = total % 60;
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
	}

	/**
	 * Splits a list of detection windows for a single track (sorted by
	 * {@code queryStart}) into clusters whenever the gap between two
	 * consecutive windows exceeds {@code gapThreshold} seconds.
	 *
	 * <p>This is what prevents a track that plays twice on a recording with a
	 * pause between occurrences from being collapsed into one fictitious match
	 * whose duration exceeds the track itself.</p>
	 */
	public static List<List<QueryResult>> clusterWindows(List<QueryResult> windows, double gapThreshold) {
		List<List<QueryResult>> clusters = new ArrayList<>();
		if (windows == null || windows.isEmpty()) return clusters;
		List<QueryResult> current = new ArrayList<>();
		current.add(windows.get(0));
		for (int i = 1; i < windows.size(); i++) {
			QueryResult w = windows.get(i);
			QueryResult prev = current.get(current.size() - 1);
			if (w.queryStart - prev.queryStop > gapThreshold) {
				clusters.add(current);
				current = new ArrayList<>();
			}
			current.add(w);
		}
		clusters.add(current);
		return clusters;
	}

	/**
	 * Effective per-track gap threshold. Capped by half of the reference
	 * duration when known so that very short tracks cannot legitimately span
	 * a 30-second gap without intermediate detection windows.
	 */
	public static double effectiveGapThreshold(double maxGap, double trackDuration) {
		if (trackDuration > 0) {
			return Math.min(maxGap, trackDuration * 0.5);
		}
		return maxGap;
	}

	/** Aggregate one cluster of detection windows into a {@link MatchWindow}. */
	private static MatchWindow buildWindow(List<QueryResult> cluster) {
		QueryResult first = cluster.get(0);
		MatchWindow w = new MatchWindow();
		w.queryStart = first.queryStart;
		w.queryEnd = first.queryStop;
		w.refStart = first.refStart;
		w.refEnd = first.refStop;
		int totalScore = 0;
		for (QueryResult r : cluster) {
			if (r.queryStart < w.queryStart) w.queryStart = r.queryStart;
			if (r.queryStop > w.queryEnd) w.queryEnd = r.queryStop;
			if (r.refStart < w.refStart) w.refStart = r.refStart;
			if (r.refStop > w.refEnd) w.refEnd = r.refStop;
			totalScore += (int) r.score;
		}
		w.score = totalScore;
		w.windowHits = cluster.size();
		return w;
	}

	/**
	 * Recompute the envelope (legacy min/max fields) and aggregate stats from
	 * the original detection windows that produced the clusters. Done before
	 * refinement so that {@code time_factor} / {@code frequency_factor} /
	 * {@code match_percentage} keep their averages-over-detections semantics.
	 */
	private static void recomputeEnvelope(MergedMatch m, List<QueryResult> group) {
		double sumTimeFactor = 0;
		double sumFreqFactor = 0;
		double sumMatchPct = 0;
		int totalScore = 0;
		for (QueryResult r : group) {
			sumTimeFactor += r.timeFactor;
			sumFreqFactor += r.frequencyFactor;
			sumMatchPct += r.percentOfSecondsWithMatches;
			totalScore += (int) r.score;
		}
		m.score = totalScore;
		m.timeFactor = sumTimeFactor / group.size();
		m.frequencyFactor = sumFreqFactor / group.size();
		m.matchPercentage = sumMatchPct / group.size();
		m.windowHits = group.size();
		recomputeEnvelopeFromWindows(m);
	}

	/** Recompute the legacy envelope (min/max query/ref times) from windows. */
	private static void recomputeEnvelopeFromWindows(MergedMatch m) {
		if (m.windows.isEmpty()) return;
		MatchWindow first = m.windows.get(0);
		m.queryStart = first.queryStart;
		m.queryEnd = first.queryEnd;
		m.refStart = first.refStart;
		m.refEnd = first.refEnd;
		for (MatchWindow w : m.windows) {
			if (w.queryStart < m.queryStart) m.queryStart = w.queryStart;
			if (w.queryEnd > m.queryEnd) m.queryEnd = w.queryEnd;
			if (w.refStart < m.refStart) m.refStart = w.refStart;
			if (w.refEnd > m.refEnd) m.refEnd = w.refEnd;
		}
	}

	/**
	 * Refines the start and end of a single occurrence (cluster) by re-querying
	 * small chunks around its estimated boundaries.
	 *
	 * <p>The probe range is bounded by {@code gapThreshold} so refinement
	 * cannot reach across the gap that separates this cluster from a sibling
	 * cluster of the same track and stitch them into one fictitious match.</p>
	 */
	private static void refineWindowBoundaries(Strategy strategy, String recordingPath,
											   String targetId, String filename,
											   MatchWindow w, double trackDuration,
											   double gapThreshold) throws IOException {

		double refineThreshold = Config.getFloat(Key.MONITOR_REFINE_THRESHOLD);
		int refineChunkSize = Config.getInt(Key.MONITOR_REFINE_CHUNK_SIZE);
		int maxResults = Config.getInt(Key.NUMBER_OF_QUERY_RESULTS);

		// --- Refine START ---
		if (w.refStart >= refineThreshold) {
			double estimatedStart = w.queryStart - w.refStart;
			// Cap probe distance so we cannot land inside the previous cluster.
			double earliest = Math.max(0, w.queryStart - gapThreshold);
			estimatedStart = Math.max(estimatedStart, earliest);
			double bestQueryStart = w.queryStart;
			double bestRefStart = w.refStart;

			double[] offsets = {
				Math.max(0, w.queryStart - refineChunkSize),
				Math.max(0, (w.queryStart + estimatedStart) / 2 - 5),
				Math.max(0, estimatedStart - 5)
			};

			for (double chunkOffset : offsets) {
				Path chunk = extractAudioChunkDouble(recordingPath, chunkOffset, refineChunkSize);
				try {
					CollectingResultHandler handler = new CollectingResultHandler();
					strategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

					for (QueryResult r : handler.results) {
						if (r.refIdentifier.equals(targetId)) {
							double absoluteQueryStart = r.queryStart + chunkOffset;
							if (absoluteQueryStart < bestQueryStart && absoluteQueryStart >= earliest) {
								bestQueryStart = absoluteQueryStart;
								bestRefStart = r.refStart;
							}
							break;
						}
					}
				} finally {
					try { Files.deleteIfExists(chunk); } catch (IOException ignored) {}
				}
			}

			if (bestQueryStart < w.queryStart) {
				w.queryStart = bestQueryStart;
				w.refStart = bestRefStart;
				LOG.info(String.format("Refined START for %s: query_start=%.1f, match_start=%.1f",
						targetId, w.queryStart, w.refStart));
			}
		}

		// --- Refine END ---
		double gapAtEnd = (trackDuration > 0) ? (trackDuration - w.refEnd) : refineChunkSize;
		// Bounded probe: cannot extend further than gapThreshold past current
		// queryEnd, so it cannot cross into the next cluster of the same track.
		gapAtEnd = Math.min(gapAtEnd, gapThreshold);
		if (gapAtEnd >= refineThreshold) {
			double estimatedEnd = w.queryEnd + gapAtEnd;
			double latest = w.queryEnd + gapThreshold;
			double bestQueryEnd = w.queryEnd;
			double bestRefEnd = w.refEnd;

			double[] offsets = {
				Math.max(0, w.queryEnd - 5),
				Math.max(0, (w.queryEnd + estimatedEnd) / 2 - refineChunkSize / 2.0),
				Math.max(0, estimatedEnd - refineChunkSize + 5)
			};

			for (double chunkOffset : offsets) {
				Path chunk = extractAudioChunkDouble(recordingPath, chunkOffset, refineChunkSize);
				try {
					CollectingResultHandler handler = new CollectingResultHandler();
					strategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

					for (QueryResult r : handler.results) {
						if (r.refIdentifier.equals(targetId)) {
							double absoluteQueryEnd = r.queryStop + chunkOffset;
							if (absoluteQueryEnd > bestQueryEnd && absoluteQueryEnd <= latest) {
								bestQueryEnd = absoluteQueryEnd;
								bestRefEnd = r.refStop;
							}
							break;
						}
					}
				} finally {
					try { Files.deleteIfExists(chunk); } catch (IOException ignored) {}
				}
			}

			if (bestQueryEnd > w.queryEnd) {
				w.queryEnd = bestQueryEnd;
				w.refEnd = bestRefEnd;
				LOG.info(String.format("Refined END for %s: query_end=%.1f, match_end=%.1f",
						targetId, w.queryEnd, w.refEnd));
			}
		}
	}

	static class MergedMatch {
		String identifier;
		String isrc;
		String filename;
		double queryStart;
		double queryEnd;
		double refStart;
		double refEnd;
		int score;
		double timeFactor;
		double frequencyFactor;
		double matchPercentage;
		int windowHits;
		/**
		 * Per-occurrence breakdown: each entry corresponds to a contiguous group
		 * of detection windows for this track. The envelope fields above are the
		 * min/max across all windows (kept for backwards compatibility).
		 */
		List<MatchWindow> windows = new ArrayList<>();
	}

	/**
	 * One contiguous occurrence of a track inside the monitored recording.
	 * Multiple {@code MatchWindow}s can belong to the same {@link MergedMatch}
	 * when the same track plays more than once with a gap between occurrences.
	 */
	static class MatchWindow {
		double queryStart;
		double queryEnd;
		double refStart;
		double refEnd;
		int score;
		int windowHits;
	}

	static class CollectingResultHandler implements QueryResultHandler {
		final List<QueryResult> results = new ArrayList<>();

		@Override
		public void handleQueryResult(QueryResult result) {
			results.add(result);
		}

		@Override
		public void handleEmptyResult(QueryResult result) {
			// No match for this window — skip
		}
	}

	/**
	 * Parse comma-separated ISRCs from a parameter value.
	 */
	static Set<String> parseIsrcsParam(String isrcsParam) {
		Set<String> isrcs = new HashSet<>();
		for (String s : isrcsParam.split(",")) {
			String trimmed = s.trim();
			if (!trimmed.isEmpty()) {
				isrcs.add(trimmed);
			}
		}
		return isrcs;
	}

	/**
	 * Filter query results to only include matches for the given ISRCs.
	 */
	static List<QueryResult> filterByIsrcs(List<QueryResult> results, Set<String> isrcs) {
		List<QueryResult> filtered = new ArrayList<>();
		for (QueryResult r : results) {
			String isrc = HttpUtil.extractIsrc(r.refPath);
			if (isrc != null && isrcs.contains(isrc)) {
				filtered.add(r);
			}
		}
		return filtered;
	}
}
