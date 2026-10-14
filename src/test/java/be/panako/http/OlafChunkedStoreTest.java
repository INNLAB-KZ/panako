package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.QueryResultHandler;
import be.panako.strategy.olaf.OlafStrategy;
import be.panako.strategy.olaf.storage.OlafResourceMetadata;
import be.panako.strategy.olaf.storage.OlafStorageMemory;
import be.panako.util.Config;
import be.panako.util.FileUtils;
import be.panako.util.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class OlafChunkedStoreTest {
	@TempDir Path directory;

	@Test
	void storeNumbersRemainValidJsonInCommaLocale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.GERMANY);
			assertEquals("305.3", HttpUtil.formatOneDecimal(305.25));
			assertEquals("99.8", HttpUtil.formatPercentage(0.998));
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	void matchesAcrossFiveMinuteBoundaryAtOriginalOffset() throws Exception {
		Config config = Config.getInstance();
		config.set(Key.OLAF_STORAGE, "MEMORY");
		config.set(Key.OLAF_CACHE_TO_FILE, "FALSE");
		config.set(Key.OLAF_USE_CACHED_PRINTS, "FALSE");
		Path track = directory.resolve("long-track.wav");
		Path clip = directory.resolve("clip.wav");
		run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "anoisesrc=d=305:c=white:r=16000:s=42",
				"-ac", "1", "-y", track.toString());
		run("ffmpeg", "-v", "error", "-ss", "294", "-t", "11", "-i", track.toString(),
				"-y", clip.toString());

		OlafStrategy strategy = new OlafStrategy();
		assertEquals(305, strategy.store(track.toString(), track.getFileName().toString()), 1);
		OlafResourceMetadata metadata = OlafStorageMemory.getInstance()
				.getMetadata(FileUtils.getIdentifier(track.toString()));
		assertNotNull(metadata);
		assertTrue(metadata.numFingerprints > 0);
		assertEquals(305, metadata.duration, 1);

		List<QueryResult> results = new ArrayList<>();
		strategy.query(clip.toString(), 5, new HashSet<>(), new QueryResultHandler() {
			@Override public void handleQueryResult(QueryResult result) { results.add(result); }
			@Override public void handleEmptyResult(QueryResult result) { }
		});
		assertTrue(results.stream().anyMatch(result ->
				String.valueOf(FileUtils.getIdentifier(track.toString())).equals(result.refIdentifier)
						&& result.refStart >= 292 && result.refStart <= 302),
				"Expected match around original 300-second boundary");
		QueryResult duplicate = LongAudioDuplicateCheck.find(
				strategy, new MatchValidator(), track.toString(), 305);
		assertNotNull(duplicate);
		assertEquals(String.valueOf(FileUtils.getIdentifier(track.toString())), duplicate.refIdentifier);
		Path changed = directory.resolve("changed-interior.wav");
		run("ffmpeg", "-v", "error", "-i", track.toString(), "-af",
				"volume=0:enable='between(t,60,90)'", "-y", changed.toString());
		assertNull(LongAudioDuplicateCheck.find(strategy, new MatchValidator(), changed.toString(), 305),
				"Shared start, middle and end must not hide a changed interior");
	}

	@Test
	void boundedWindowsCoverInteriorAndTail() {
		List<Double> offsets = LongAudioDuplicateCheck.offsets(305);
		assertEquals(0.0, offsets.get(0));
		assertEquals(275.0, offsets.get(offsets.size() - 1));
		for (int i = 1; i < offsets.size(); i++) {
			assertTrue(offsets.get(i) <= offsets.get(i - 1) + 30);
		}
		assertThrows(IllegalArgumentException.class, () -> LongAudioDuplicateCheck.offsets(Double.NaN));
	}

	@Test
	void sharedExcerptOrShiftedWindowIsNotWholeAudioDuplicate() {
		QueryResult full = new QueryResult("q", 0.1, 29.8, "r", "1", 58.1, 87.8, 100, 1, 1, 1);
		assertTrue(LongAudioDuplicateCheck.aligned(full, 58, 0.0));
		assertFalse(LongAudioDuplicateCheck.aligned(full, 87, 0.0));
		QueryResult excerpt = new QueryResult("q", 10, 15, "r", "1", 10, 15, 100, 1, 1, 1);
		assertFalse(LongAudioDuplicateCheck.aligned(excerpt, 0, null));
	}

	private static void run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		assertEquals(0, process.waitFor(), output);
	}
}
