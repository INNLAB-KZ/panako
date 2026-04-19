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

		for (int t = 0; t + stepSize < totalDuration; t += actualStep) {
			Path chunk = extractAudioChunk(filePath, t, stepSize);
			try {
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

	private static List<QueryResult> monitorParallel(Strategy strategy, String filePath,
			int stepSize, int actualStep, int maxResults, double totalDuration, int parallelism) throws IOException {
		List<Integer> offsets = new ArrayList<>();
		for (int t = 0; t + stepSize < totalDuration; t += actualStep) {
			offsets.add(t);
		}

		List<QueryResult> allResults = Collections.synchronizedList(new ArrayList<>());
		ExecutorService executor = Executors.newFixedThreadPool(parallelism);

		List<Future<?>> futures = new ArrayList<>();
		for (int t : offsets) {
			futures.add(executor.submit(() -> {
				Path chunk = null;
				try {
					chunk = extractAudioChunk(filePath, t, stepSize);
					Strategy localStrategy = new OlafStrategy();
					CollectingResultHandler handler = new CollectingResultHandler();
					localStrategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

					for (QueryResult r : handler.results) {
						allResults.add(new QueryResult(
								r.queryPath, r.queryStart + t, r.queryStop + t,
								r.refPath, r.refIdentifier, r.refStart, r.refStop,
								r.score, r.timeFactor, r.frequencyFactor,
								r.percentOfSecondsWithMatches));
					}
				} catch (IOException e) {
					LOG.log(Level.WARNING, "Failed to process window at offset " + t, e);
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
	 * Get audio duration in seconds using ffprobe.
	 */
	static double getAudioDuration(String filePath) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(
				"ffprobe", "-v", "error",
				"-show_entries", "format=duration",
				"-of", "default=noprint_wrappers=1:nokey=1",
				filePath);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String output = new String(p.getInputStream().readAllBytes()).trim();
		try {
			p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			return Double.parseDouble(output);
		} catch (NumberFormatException e) {
			throw new IOException("Could not determine audio duration: " + output);
		}
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
	 * Merges window results by identifier, then refines start/end boundaries.
	 *
	 * <p>Refinement logic:
	 * <ul>
	 *   <li>If match_start >= 5s: the track began before our first detection window.
	 *       Extract 30s from (query_start - match_start) and re-query to find precise start.</li>
	 *   <li>If (ref_duration - match_end) >= 5s: the track continued after our last detection window.
	 *       Extract 30s from (query_end) and re-query to find precise end.</li>
	 * </ul>
	 */
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

		// Merge each group
		List<MergedMatch> merged = new ArrayList<>();
		for (Map.Entry<String, List<QueryResult>> entry : byId.entrySet()) {
			List<QueryResult> group = entry.getValue();
			group.sort(Comparator.comparingDouble(r -> r.queryStart));

			QueryResult first = group.get(0);
			double queryStart = first.queryStart;
			double queryEnd = first.queryStop;
			double refStart = first.refStart;
			double refEnd = first.refStop;
			int totalScore = 0;
			double sumTimeFactor = 0;
			double sumFreqFactor = 0;
			double sumMatchPct = 0;

			for (QueryResult r : group) {
				if (r.queryStart < queryStart) queryStart = r.queryStart;
				if (r.queryStop > queryEnd) queryEnd = r.queryStop;
				if (r.refStart < refStart) refStart = r.refStart;
				if (r.refStop > refEnd) refEnd = r.refStop;
				totalScore += (int) r.score;
				sumTimeFactor += r.timeFactor;
				sumFreqFactor += r.frequencyFactor;
				sumMatchPct += r.percentOfSecondsWithMatches;
			}

			MergedMatch m = new MergedMatch();
			m.identifier = first.refIdentifier;
			m.isrc = HttpUtil.extractIsrc(first.refPath);
			m.filename = first.refPath;
			m.queryStart = queryStart;
			m.queryEnd = queryEnd;
			m.refStart = refStart;
			m.refEnd = refEnd;
			m.score = totalScore;
			m.timeFactor = sumTimeFactor / group.size();
			m.frequencyFactor = sumFreqFactor / group.size();
			m.matchPercentage = sumMatchPct / group.size();
			m.windowHits = group.size();
			merged.add(m);
		}

		// Refinement pass — refine start and end for each track
		if (recordingPath != null && strategy != null) {
			for (MergedMatch m : merged) {
				try {
					refineMatchBoundaries(strategy, recordingPath, m);
				} catch (Exception e) {
					LOG.log(Level.WARNING, "Refinement failed for " + m.isrc + ": " + e.getMessage());
				}
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
			json.append("\"window_hits\":").append(m.windowHits);
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
	 * Refines the start and end boundaries of a merged match by re-querying
	 * small chunks around the estimated boundaries.
	 *
	 * <p>For start: if match_start >= refineThreshold, we missed the beginning.
	 * We estimate the real start at (query_start - match_start), extract a 30s chunk
	 * there and query to find the precise start.</p>
	 *
	 * <p>For end: we get the reference track duration from metadata. If
	 * (ref_duration - match_end) >= refineThreshold, the track continued playing.
	 * We extract a 30s chunk after query_end and query to find the precise end.</p>
	 */
	private static void refineMatchBoundaries(Strategy strategy, String recordingPath, MergedMatch m)
			throws IOException {

		double refineThreshold = Config.getFloat(Key.MONITOR_REFINE_THRESHOLD);
		int refineChunkSize = Config.getInt(Key.MONITOR_REFINE_CHUNK_SIZE);
		int maxResults = Config.getInt(Key.NUMBER_OF_QUERY_RESULTS);
		String targetId = m.identifier;

		// --- Refine START ---
		// Try multiple chunks working backward from the first detection to find the true start.
		// Chunk 1: just before query_start (captures audio right before first detection)
		// Chunk 2: further back, near the estimated start (if the track plays from beginning)
		if (m.refStart >= refineThreshold) {
			double estimatedStart = m.queryStart - m.refStart;
			double bestQueryStart = m.queryStart;
			double bestRefStart = m.refStart;

			// Chunk positions to try: close to detection first, then further back
			double[] offsets = {
				Math.max(0, m.queryStart - refineChunkSize),  // right before first detection
				Math.max(0, (m.queryStart + estimatedStart) / 2 - 5),  // midpoint
				Math.max(0, estimatedStart - 5)  // near estimated start
			};

			for (double chunkOffset : offsets) {
				Path chunk = extractAudioChunkDouble(recordingPath, chunkOffset, refineChunkSize);
				try {
					CollectingResultHandler handler = new CollectingResultHandler();
					strategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

					for (QueryResult r : handler.results) {
						if (r.refIdentifier.equals(targetId)) {
							double absoluteQueryStart = r.queryStart + chunkOffset;
							if (absoluteQueryStart < bestQueryStart) {
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

			if (bestQueryStart < m.queryStart) {
				m.queryStart = bestQueryStart;
				m.refStart = bestRefStart;
				LOG.info(String.format("Refined START for %s: query_start=%.1f, match_start=%.1f",
						m.isrc, m.queryStart, m.refStart));
			}
		}

		// --- Refine END ---
		// Always try to refine end — look further in the recording for more of the track.
		// If we have metadata, use ref duration to estimate. Otherwise, just probe ahead.
		double refDuration = -1;
		try {
			String metadata = strategy.metadata(m.filename);
			double[] parsed = HttpUtil.parseMetadata(metadata);
			if (parsed != null) {
				refDuration = parsed[0];
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "Could not get metadata for " + m.filename, e);
		}

		// Calculate gap: if we know ref duration, use it. Otherwise assume there's more to find.
		double gapAtEnd = (refDuration > 0) ? (refDuration - m.refEnd) : refineChunkSize;
		if (gapAtEnd >= refineThreshold) {
			double estimatedEnd = m.queryEnd + gapAtEnd;
			double bestQueryEnd = m.queryEnd;
			double bestRefEnd = m.refEnd;

				// Chunk positions: right after last detection, midpoint, near estimated end
				double[] offsets = {
					Math.max(0, m.queryEnd - 5),  // right after last detection
					Math.max(0, (m.queryEnd + estimatedEnd) / 2 - refineChunkSize / 2.0),  // midpoint
					Math.max(0, estimatedEnd - refineChunkSize + 5)  // near estimated end
				};

				for (double chunkOffset : offsets) {
					Path chunk = extractAudioChunkDouble(recordingPath, chunkOffset, refineChunkSize);
					try {
						CollectingResultHandler handler = new CollectingResultHandler();
						strategy.query(chunk.toAbsolutePath().toString(), maxResults, new HashSet<>(), handler);

						for (QueryResult r : handler.results) {
							if (r.refIdentifier.equals(targetId)) {
								double absoluteQueryEnd = r.queryStop + chunkOffset;
								if (absoluteQueryEnd > bestQueryEnd) {
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

				if (bestQueryEnd > m.queryEnd) {
					m.queryEnd = bestQueryEnd;
					m.refEnd = bestRefEnd;
					LOG.info(String.format("Refined END for %s: query_end=%.1f, match_end=%.1f",
							m.isrc, m.queryEnd, m.refEnd));
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
