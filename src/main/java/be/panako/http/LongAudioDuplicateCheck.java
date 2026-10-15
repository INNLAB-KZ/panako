package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.QueryResultHandler;
import be.panako.strategy.Strategy;
import be.panako.strategy.olaf.OlafStrategy;
import be.panako.util.Config;
import be.panako.util.Key;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded content check for audio too long to query as one file. */
final class LongAudioDuplicateCheck {
	static final int SAMPLE_SECONDS = 30;
	private LongAudioDuplicateCheck() {}

	/** Overlapping windows cover every second, including a full-sized final window. */
	static List<Double> offsets(double duration) {
		if (!Double.isFinite(duration) || duration <= 0) {
			throw new IllegalArgumentException("Audio duration must be positive and finite");
		}
		List<Double> result = new ArrayList<>();
		double last = Math.max(0, duration - SAMPLE_SECONDS);
		for (double offset = 0; offset < last; offset += SAMPLE_SECONDS - 1) result.add(offset);
		result.add(last);
		return result;
	}

	static boolean aligned(QueryResult result, double offset, Double firstAlignment) {
		// Dense hits in a small shared excerpt must not classify a whole upload as a duplicate.
		if (result.queryStop - result.queryStart < SAMPLE_SECONDS - 2) return false;
		double alignment = result.refStart - result.queryStart - offset;
		return Double.isFinite(alignment)
				&& (firstAlignment == null || Math.abs(alignment - firstAlignment) <= 2);
	}

	private static Path extract(String filePath, double offset) throws IOException {
		Path chunk = Files.createTempFile("panako_store_check_", ".wav");
		Process process = null;
		boolean complete = false;
		try {
			process = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-ss",
					String.format(java.util.Locale.ROOT, "%.3f", offset), "-t", String.valueOf(SAMPLE_SECONDS),
					"-i", filePath, "-ar", String.valueOf(Config.getInt(Key.OLAF_SAMPLE_RATE)),
					"-ac", "1", chunk.toString()).redirectError(ProcessBuilder.Redirect.DISCARD)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
				throw new IOException("Duplicate-check audio extraction failed at " + offset);
			}
			double decoded = be.panako.util.AudioFileUtils.audioFileDurationInSeconds(chunk.toFile());
			if (!Double.isFinite(decoded) || decoded < SAMPLE_SECONDS - 0.5) {
				throw new IOException("Duplicate-check audio extraction was truncated at " + offset);
			}
			complete = true;
			return chunk;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Duplicate check interrupted", e);
		} finally {
			if (process != null && process.isAlive()) process.destroyForcibly();
			if (!complete) Files.deleteIfExists(chunk);
		}
	}

	static QueryResult find(Strategy strategy, MatchValidator validator, String filePath, double duration) {
		Map<String, QueryResult> candidates = null;
		Map<String, Double> alignments = new LinkedHashMap<>();
		List<Double> offsets = offsets(duration);
		for (double offset : offsets) {
			Path sample = null;
			try {
				sample = extract(filePath, offset);
				List<QueryResult> results = new ArrayList<>();
				strategy.query(sample.toString(), Config.getInt(Key.NUMBER_OF_QUERY_RESULTS),
						new HashSet<>(), new QueryResultHandler() {
							@Override public void handleQueryResult(QueryResult result) { results.add(result); }
							@Override public void handleEmptyResult(QueryResult result) { }
						});
				Map<String, QueryResult> matches = new LinkedHashMap<>();
				for (QueryResult result : results) {
					if (candidates != null && !candidates.containsKey(result.refIdentifier)) continue;
					if (validator.validateQuality(result)
							&& validator.validate(result, SAMPLE_SECONDS)
							&& result.percentOfSecondsWithMatches >= 0.99
							&& aligned(result, offset, alignments.get(result.refIdentifier))) {
						matches.putIfAbsent(result.refIdentifier, result);
						alignments.putIfAbsent(result.refIdentifier, result.refStart - result.queryStart - offset);
					}
				}
				if (matches.isEmpty()) return null;
				candidates = matches;
			} catch (IOException e) {
				throw new IllegalStateException("Could not check long audio for duplicates", e);
			} finally {
				if (sample != null) {
					try { Files.deleteIfExists(sample); } catch (IOException ignored) { }
				}
			}
		}
		if (candidates == null) return null;
		for (QueryResult candidate : candidates.values()) {
			// A metadata rename preserves resource_id but can change the path hash.
			String metadata = strategy instanceof OlafStrategy
					? ((OlafStrategy) strategy).metadataByIdentifier(Long.parseLong(candidate.refIdentifier))
					: strategy.metadata(candidate.refPath);
			double[] reference = HttpUtil.parseMetadata(metadata);
			if (reference != null && Math.abs(reference[0] - duration) <= 5) return candidate;
		}
		return null;
	}
}
