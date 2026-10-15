package be.panako.http;

import be.panako.strategy.olaf.OlafStrategy;
import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the duplicate scan SQL against a disposable local ClickHouse, for example:
 * {@code docker run -d -p 18123:8123 -e CLICKHOUSE_PASSWORD=test clickhouse/clickhouse-server} and
 * {@code DUPLICATE_SCAN_CLICKHOUSE_URL='jdbc:ch://localhost:18123?user=default&password=test' ./gradlew test}.
 * The test drops and recreates its own database, so it refuses non-local servers.
 */
class DuplicateScanClickHouseTest {
	private static final String DATABASE = "panako_duplicate_test";
	@TempDir Path directory;

	@Test
	void scanFindsShiftedCopyAndIgnoresUnrelatedTracks() throws Exception {
		String server = System.getenv("DUPLICATE_SCAN_CLICKHOUSE_URL");
		assumeTrue(server != null && !server.isEmpty(), "DUPLICATE_SCAN_CLICKHOUSE_URL not set");
		assertTrue(server.matches("jdbc:ch://(localhost|127\\.0\\.0\\.1):[0-9]+(\\?.*)?"),
				"Only a local throwaway ClickHouse may be used");
		String base = server.replaceAll("\\?.*", "");
		String query = server.substring(base.length());
		try (Connection conn = DriverManager.getConnection(server); Statement stmt = conn.createStatement()) {
			stmt.execute("DROP DATABASE IF EXISTS " + DATABASE);
			stmt.execute("CREATE DATABASE " + DATABASE);
		}
		Config config = Config.getInstance();
		config.set(Key.OLAF_STORAGE, "CLICKHOUSE");
		config.set(Key.OLAF_CLICKHOUSE_URL, base + "/" + DATABASE + query);
		config.set(Key.OLAF_CACHE_TO_FILE, "FALSE");
		config.set(Key.OLAF_USE_CACHED_PRINTS, "FALSE");

		Path original = directory.resolve("201.wav");
		Path copy = directory.resolve("202.wav");
		Path unrelated = directory.resolve("203.wav");
		Path excerpt = directory.resolve("204.wav");
		run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", noise(40, 7), "-y", original.toString());
		// Same master, other release: quieter, with 0.256 s more leading silence. This test checks the scan
		// mechanics; recognition thresholds for real lossy releases were calibrated on production pairs.
		run("ffmpeg", "-v", "error", "-i", original.toString(), "-af", "adelay=256,volume=0.8",
				"-y", copy.toString());
		run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", noise(40, 8), "-y", unrelated.toString());
		run("ffmpeg", "-v", "error", "-t", "20", "-i", original.toString(), "-y", excerpt.toString());

		OlafStrategy strategy = new OlafStrategy();
		for (Path track : Arrays.asList(original, copy, unrelated, excerpt)) {
			strategy.store(track.toString(), track.getFileName().toString());
		}
		OlafStorageClickHouse storage = OlafStorageClickHouse.getInstance();
		long rows;
		try (Connection conn = storage.openConnection(); Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT count() FROM olaf_fingerprints")) {
			rs.next();
			rows = rs.getLong(1);
		}
		DuplicateScanner.Settings settings = new DuplicateScanner.Settings(rows / 4 + 1, 256, 10, 15, 0.5, 2,
				Config.getInt(Key.OLAF_SAMPLE_RATE), Config.getInt(Key.OLAF_STEP_SIZE));
		DuplicateScanner scanner = new DuplicateScanner(storage::openConnection);
		try (Connection conn = storage.openConnection(); Statement stmt = conn.createStatement()) {
			assertTrue(DuplicateScanner.partitions(stmt, settings).size() >= 3, "Scan must span several partitions");
		}

		assertEquals(1, scanner.scan("first", settings));
		assertEquals(1, scanner.scan("second", settings), "A rescan replaces the previous results");
		DuplicateScanner.ScanRecord scan = scanner.latestScan();
		assertEquals("second", scan.scanId);
		List<DuplicateAccumulator.Pair> pairs = scanner.loadPairs(scan.scanId, 0.5);
		assertEquals(1, pairs.size(), "Excerpt differs too much in duration; unrelated track never matches");
		DuplicateAccumulator.Pair pair = pairs.get(0);
		assertEquals(201, pair.a);
		assertEquals(202, pair.b);
		assertTrue(pair.similarity() >= 0.9, "Copy similarity " + pair.similarity());
		assertEquals(0.256, pair.offsetSeconds, 0.02);

		Map<Long, DuplicateScanner.TrackInfo> tracks = scanner.loadTracks(scan.scanId, Arrays.asList(201L, 202L));
		assertEquals(2, tracks.size());
		assertTrue(tracks.get(201L).fingerprints > 0);
		assertEquals(copy.toString(), tracks.get(202L).path);

		List<DuplicateAccumulator.Pair> verified = scanner.verify(201, Arrays.asList(202L, 203L, 204L), settings);
		DuplicateAccumulator.Pair copyPair = find(verified, 202);
		assertEquals(pair.similarity(), copyPair.similarity(), 0.02, "Delete check agrees with the scan");
		DuplicateAccumulator.Pair excerptPair = find(verified, 204);
		assertTrue(excerptPair.coverageB > 0.95 && excerptPair.coverageA < 0.6,
				"Excerpt is contained in the original but covers only half of it");
		DuplicateAccumulator.Pair unrelatedPair = find(verified, 203);
		assertTrue(unrelatedPair == null || unrelatedPair.similarity() < 0.1);

		// Deleting works from the stored scan without reading fingerprints again.
		assertTrue(scanner.complete(scan));
		List<DuplicateAccumulator.Pair> stored = scanner.storedPairs(scan.scanId, 202, Arrays.asList(201L, 203L));
		assertEquals(1, stored.size());
		assertEquals(pair.similarity(), stored.get(0).similarity(), 1e-6);

		Map<Long, Long> deleted = storage.deleteByResourceIds(Collections.singletonList(202L));
		assertTrue(deleted.get(202L) > 0);
		assertNull(storage.getMetadataExt(202), "Metadata goes at once, so the track stops matching");
		// The fingerprint delete is only queued; it must still complete.
		long left = -1;
		for (int i = 0; i < 60 && left != 0; i++) {
			if (i > 0) Thread.sleep(500);
			try (Connection conn = storage.openConnection(); Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT count() FROM olaf_fingerprints WHERE resource_id = 202")) {
				rs.next();
				left = rs.getLong(1);
			}
		}
		assertEquals(0, left, "Queued fingerprint delete completes");
		Map<Long, Long> again = storage.deleteByResourceIds(Arrays.asList(202L, 999L, 202L));
		assertEquals(2, again.size());
		assertEquals(0L, again.get(202L), "A repeated delete reports nothing left");
		assertEquals(0L, again.get(999L), "An unknown id reports nothing");
		assertTrue(scanner.loadPairs(scan.scanId, 0.5).isEmpty(), "Deleted tracks leave the list");
		scanner.logDeletions(Collections.singletonList(new DuplicateScanner.Deletion(java.time.Instant.now(),
				scan.scanId, 201, "201", 202, "202", pair.similarity(), "scan:" + scan.scanId, deleted.get(202L))));
		assertEquals(1, scanner.scan("third", settings) + 1, "Rescan after delete finds no pairs");
		List<DuplicateScanner.Deletion> log = scanner.deletions(10, 0);
		assertEquals(1, log.size(), "Rescans keep the deletion log");
		assertEquals("202", log.get(0).isrc);
		assertEquals(201, log.get(0).keep);
	}

	private static String noise(int seconds, int seed) {
		return "anoisesrc=d=" + seconds + ":c=pink:r=16000:s=" + seed;
	}

	private static DuplicateAccumulator.Pair find(List<DuplicateAccumulator.Pair> pairs, long other) {
		return pairs.stream().filter(p -> p.a == other || p.b == other).findFirst().orElse(null);
	}

	private static void run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		assertEquals(0, process.waitFor(), output);
	}
}
