package be.panako.http;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateDetectionTest {
	private static final DuplicateScanner.Settings SETTINGS =
			new DuplicateScanner.Settings(20_000_000, 256, 10, 15, 0.5, 4, 16000, 128);

	private static int[] range(int from, int to) {
		int[] values = new int[to - from];
		for (int i = from; i < to; i++) values[i - from] = i;
		return values;
	}

	@Test
	void settingsKeepEveryAllowedOffsetInNonNegativeBuckets() {
		assertEquals(1250, SETTINGS.maxOffsetFrames);
		int shift = SETTINGS.bucketShiftFrames();
		assertEquals(0, shift % DuplicateScanner.BUCKET_FRAMES);
		assertTrue(shift >= SETTINGS.maxOffsetFrames);
		assertEquals("toUInt32(intDiv(t1 * 128, 16000))", SETTINGS.second("t1"));
	}

	@Test
	void similarityMergesNeighbouringOffsetsAndUsesEligibleSeconds() {
		DuplicateAccumulator accumulator = SETTINGS.newAccumulator();
		int peak = SETTINGS.bucketShiftFrames() / DuplicateScanner.BUCKET_FRAMES;
		accumulator.addTrack(1, 1000, range(0, 100));
		accumulator.addTrack(2, 900, range(0, 50));
		accumulator.addTrack(2, 100, range(50, 100));
		// Jitter splits one alignment over two partitions and two neighbouring buckets.
		accumulator.addPairBucket(1, 2, peak, 300, range(0, 60), range(0, 60));
		accumulator.addPairBucket(1, 2, peak + 1, 200, range(55, 98), range(55, 97));
		// A weaker, unrelated offset must not add coverage.
		accumulator.addPairBucket(1, 2, peak + 40, 50, range(98, 100), range(97, 100));

		List<DuplicateAccumulator.Pair> pairs = accumulator.pairs(0.5);
		assertEquals(1, pairs.size());
		DuplicateAccumulator.Pair pair = pairs.get(0);
		assertEquals(500, pair.alignedHits);
		assertEquals(0.98, pair.coverageA, 1e-9);
		assertEquals(0.97, pair.coverageB, 1e-9);
		assertEquals(0.97, pair.similarity(), 1e-9);
		assertEquals(1000, accumulator.track(2).fingerprints);
		assertEquals(2 / 125.0, pair.offsetSeconds, 1e-9, "Bucket centre of a zero offset");
		assertTrue(accumulator.pairs(0.98).isEmpty());
	}

	@Test
	void pairsNeedOrderedIdsAndKnownTracks() {
		DuplicateAccumulator accumulator = SETTINGS.newAccumulator();
		assertThrows(IllegalArgumentException.class,
				() -> accumulator.addPairBucket(2, 1, 0, 5, new int[0], new int[0]));
		accumulator.addPairBucket(1, 2, 0, 5, new int[]{1}, new int[]{1});
		assertTrue(accumulator.pairs(0).isEmpty(), "Tracks without eligible seconds cannot be scored");
	}

	@Test
	void comparePrintsMatchesTheScanScore() {
		Map<Long, List<long[]>> byHash = new HashMap<>();
		long hash = 0;
		for (int t1 = 0; t1 < 125 * 20; t1 += 25) {
			// Track 7 is the kept copy; track 3 is the same audio 2 frames later; track 9 is unrelated.
			byHash.put(hash++, new ArrayList<>(Arrays.asList(new long[]{7, t1}, new long[]{3, t1 + 2})));
			byHash.put(hash++, new ArrayList<>(Arrays.asList(new long[]{9, t1}, new long[]{7, t1 + 50 * 125})));
		}
		List<DuplicateAccumulator.Pair> pairs = DuplicateScanner.comparePrints(7, byHash, SETTINGS);
		DuplicateAccumulator.Pair copy = pairs.stream().filter(p -> p.a == 3).findFirst().orElseThrow();
		assertEquals(7, copy.b);
		assertEquals(1.0, copy.coverageA, 1e-9);
		assertEquals(0.5, copy.coverageB, 1e-9, "Kept track also has seconds that only match track 9");
		assertEquals(-2 / 125.0, copy.offsetSeconds, 0.02);
		assertTrue(pairs.stream().noneMatch(p -> p.b == 9), "Offsets beyond the limit are not compared");
	}

	@Test
	void groupsAreConnectedComponentsWithRecommendedKeep() {
		List<DuplicateAccumulator.Pair> pairs = Arrays.asList(
				new DuplicateAccumulator.Pair(10, 20, 0, 900, 0.99, 0.98),
				new DuplicateAccumulator.Pair(20, 30, 0, 800, 0.97, 0.96),
				new DuplicateAccumulator.Pair(40, 50, 0, 100, 0.70, 0.68));
		List<DuplicateGroups.Group> groups = DuplicateGroups.build(pairs);
		assertEquals(2, groups.size());
		DuplicateGroups.Group first = groups.get(0);
		assertEquals(Arrays.asList(10L, 20L, 30L), first.members);
		assertEquals(0.98, first.maxSimilarity(), 1e-9);
		assertEquals(0.96, first.minSimilarity(), 1e-9);
		assertEquals(0.97, first.meanSimilarity(20), 1e-9);
		assertEquals(0.49, first.meanSimilarity(10), 1e-9);

		Map<Long, DuplicateScanner.TrackInfo> tracks = new HashMap<>();
		tracks.put(10L, new DuplicateScanner.TrackInfo(10, "A.mp3", 200, null, null, 12000, 199));
		tracks.put(20L, new DuplicateScanner.TrackInfo(20, "B.m4a", 200, null, null, 11000, 199));
		tracks.put(30L, new DuplicateScanner.TrackInfo(30, "C.mp3", 200, null, null, 13000, 199));
		assertEquals(20L, DuplicateGroups.recommend(first, tracks), "Most representative copy wins");
		tracks.remove(20L);
		assertEquals(10L, DuplicateGroups.recommend(first, tracks), "Deleted tracks are never recommended");

		DuplicateGroups.Group second = groups.get(1);
		tracks.put(40L, new DuplicateScanner.TrackInfo(40, "D.mp3", 197, null, null, 13000, 150));
		tracks.put(50L, new DuplicateScanner.TrackInfo(50, "E.mp3", 204, null, null, 13000, 150));
		assertEquals(40L, DuplicateGroups.recommend(second, tracks), "Higher fingerprint density wins a tie");
	}

	@Test
	void listJsonLabelsDuplicatesAndSimilarTracks() {
		List<DuplicateAccumulator.Pair> pairs = Arrays.asList(
				new DuplicateAccumulator.Pair(10, 20, 0.016, 900, 0.99, 0.98),
				new DuplicateAccumulator.Pair(40, 50, -0.088, 100, 0.70, 0.68));
		List<DuplicateGroups.Group> groups = DuplicateGroups.build(pairs);
		Map<Long, DuplicateScanner.TrackInfo> tracks = new HashMap<>();
		tracks.put(10L, new DuplicateScanner.TrackInfo(10, "/tmp/FRX1.m4a", 200, "Song \"1\"", null, 12000, 199));
		tracks.put(20L, new DuplicateScanner.TrackInfo(20, "1000.mp3", 200, null, "https://a/b", 11000, 199));
		DuplicateScanner.ScanRecord scan = new DuplicateScanner.ScanRecord("s1",
				Instant.parse("2026-10-15T10:00:00Z"), Instant.parse("2026-10-15T10:15:00Z"), 2);
		String json = DuplicatesHandler.listJson(scan, 0.5, 0.95, groups.size(), 0, groups, tracks);
		assertTrue(json.contains("\"total_groups\":2"));
		assertTrue(json.contains("\"label\":\"duplicate\",\"max_similarity\":0.980"));
		assertTrue(json.contains("\"label\":\"similar\""));
		assertTrue(json.contains("\"isrc\":\"FRX1\""));
		assertTrue(json.contains("\"title\":\"Song \\\"1\\\"\""));
		assertTrue(json.contains("\"offset_seconds\":-0.088"));
		assertFalse(json.contains("\"resource_id\":40"), "Missing tracks are left out");
	}

	@Test
	void deleteRequestParsing() {
		String body = "{\"keep\": 1000077926, \"delete\": [1000111874, 1282962679], \"dry_run\": false, \"min_similarity\": 0.9}";
		assertEquals(1000077926L, DuplicatesHandler.jsonLong(body, "keep"));
		assertEquals(Arrays.asList(1000111874L, 1282962679L), DuplicatesHandler.jsonLongArray(body, "delete"));
		assertEquals(Boolean.FALSE, DuplicatesHandler.jsonBoolean(body, "dry_run"));
		assertEquals(0.9, DuplicatesHandler.fraction(DuplicatesHandler.jsonNumber(body, "min_similarity"), 0.5));
		assertNull(DuplicatesHandler.jsonBoolean("{}", "dry_run"));
		assertThrows(IllegalArgumentException.class, () -> DuplicatesHandler.jsonLongArray("{\"delete\":[\"x\"]}", "delete"));
		assertThrows(IllegalArgumentException.class, () -> DuplicatesHandler.fraction("1.5", 0.5));
	}

	@Test
	void deleteJsonReportsVerificationFromTheKeptSide() {
		Map<Long, DuplicateAccumulator.Pair> verified = new HashMap<>();
		verified.put(5L, new DuplicateAccumulator.Pair(5, 8, 0.5, 300, 0.9, 0.8));
		String json = DuplicatesHandler.deleteJson("dry_run", 8, 0.5, "scan:s1",
				new LinkedHashSet<>(Arrays.asList(5L, 6L)), verified, new HashMap<>(), null);
		assertTrue(json.contains("\"verified_on\":\"scan:s1\""));
		assertTrue(json.contains("{\"resource_id\":5,\"isrc\":null,\"similarity\":0.800,\"verified\":true,"
				+ "\"coverage_keep\":0.800,\"coverage_track\":0.900,\"offset_seconds\":-0.500"));
		assertTrue(json.contains("{\"resource_id\":6,\"isrc\":null,\"similarity\":0.000,\"verified\":false,\"deleted\":false}"));
	}

	@Test
	void resultTablesFollowFingerprintReplication() {
		assertEquals("MergeTree()", DuplicateScanner.engine("olaf_duplicate_pairs", false));
		assertEquals("ReplicatedMergeTree('/clickhouse/tables/{shard}/olaf_duplicate_pairs', '{replica}')",
				DuplicateScanner.engine("olaf_duplicate_pairs", true));
	}

	@Test
	void scanJsonIsEmbeddedInStatus() {
		DuplicateScanner.ScanRecord scan = new DuplicateScanner.ScanRecord("s1",
				Instant.parse("2026-10-15T10:00:00Z"), Instant.parse("2026-10-15T10:15:00Z"), 7);
		DuplicateScanner.Status idle = new DuplicateScanner.Status("idle", null, 0, 0, 0, 0, null, null, null);
		assertEquals("{\"status\":\"idle\",\"last_finished_scan\":{\"scan_id\":\"s1\","
				+ "\"started_at\":\"2026-10-15T10:00:00Z\",\"finished_at\":\"2026-10-15T10:15:00Z\",\"pairs\":7}}",
				DuplicatesHandler.statusJson(idle, scan));
	}
}
