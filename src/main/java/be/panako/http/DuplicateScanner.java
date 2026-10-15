package be.panako.http;

import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.Key;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds OLAF reference tracks in ClickHouse that are copies of the same master.
 *
 * <p>{@code olaf_fingerprints} is sorted by hash, so the scan walks it in hash
 * ranges of roughly {@link Key#DUPLICATE_SCAN_PARTITION_ROWS} rows. Within a
 * range, ClickHouse groups the occurrences of each eligible hash and emits
 * every pair of tracks that share it, bucketed by time offset. Two copies of
 * one master share many hashes at one offset; unrelated tracks share a few at
 * random offsets. A pair's similarity is the fraction of each track's seconds
 * covered by hits at its strongest offset (see {@link DuplicateAccumulator}).</p>
 *
 * <p>Only hashes shared by at most {@link Key#DUPLICATE_SCAN_MAX_TRACKS_PER_HASH}
 * tracks are used, and only pairs whose offset and duration difference are
 * small, because the catalog holds billions of rows and frequent hashes would
 * otherwise produce billions of pairs. Copies of one master have near-equal
 * duration and offset, so these limits do not hide them.</p>
 *
 * <p>A finished scan writes {@code olaf_duplicate_pairs} and
 * {@code olaf_duplicate_tracks}, then records itself in
 * {@code olaf_duplicate_scans}; readers only use recorded scans. The scan is
 * meant to run once: listing and deleting work from these tables. A new scan
 * replaces the pairs and tracks of older scans. Deletions are logged in
 * {@code olaf_duplicate_deletions}, which no scan clears. When
 * {@code olaf_fingerprints} is replicated, these tables are replicated too.</p>
 */
final class DuplicateScanner {

	private static final Logger LOG = Logger.getLogger(DuplicateScanner.class.getName());

	/** Width of a time-offset bucket in fingerprint frames (32 ms at the default settings). */
	static final int BUCKET_FRAMES = 4;
	/** Bucket hits a pair needs within one partition to be kept; true copies have far more. */
	static final int MIN_PARTITION_HITS = 3;

	interface Connections {
		Connection open() throws SQLException;
	}

	/** Scan parameters, read from {@link Config} once per scan. */
	static final class Settings {
		final long partitionRows;
		final int maxTracksPerHash;
		final int maxOffsetFrames;
		final int maxDurationDiffSeconds;
		final double minSimilarity;
		final int maxThreads;
		final int sampleRate;
		final int stepSize;

		Settings(long partitionRows, int maxTracksPerHash, double maxOffsetSeconds, int maxDurationDiffSeconds,
				 double minSimilarity, int maxThreads, int sampleRate, int stepSize) {
			this.partitionRows = partitionRows;
			this.maxTracksPerHash = maxTracksPerHash;
			this.maxOffsetFrames = (int) Math.round(maxOffsetSeconds * sampleRate / stepSize);
			this.maxDurationDiffSeconds = maxDurationDiffSeconds;
			this.minSimilarity = minSimilarity;
			this.maxThreads = maxThreads;
			this.sampleRate = sampleRate;
			this.stepSize = stepSize;
		}

		static Settings fromConfig() {
			return new Settings(Long.parseLong(Config.get(Key.DUPLICATE_SCAN_PARTITION_ROWS)),
					Config.getInt(Key.DUPLICATE_SCAN_MAX_TRACKS_PER_HASH),
					Config.getFloat(Key.DUPLICATE_SCAN_MAX_OFFSET_SECONDS),
					Config.getInt(Key.DUPLICATE_SCAN_MAX_DURATION_DIFF_SECONDS),
					Config.getFloat(Key.DUPLICATE_SCAN_MIN_SIMILARITY),
					Config.getInt(Key.DUPLICATE_SCAN_MAX_THREADS),
					Config.getInt(Key.OLAF_SAMPLE_RATE),
					Config.getInt(Key.OLAF_STEP_SIZE));
		}

		double framesPerSecond() {
			return sampleRate / (double) stepSize;
		}

		/** Smallest multiple of the bucket width that keeps every allowed offset non-negative. */
		int bucketShiftFrames() {
			return (maxOffsetFrames / BUCKET_FRAMES + 1) * BUCKET_FRAMES;
		}

		DuplicateAccumulator newAccumulator() {
			return new DuplicateAccumulator(BUCKET_FRAMES, bucketShiftFrames(), framesPerSecond());
		}

		/** SQL expression for the whole second that fingerprint time {@code t1} falls in. */
		String second(String t1) {
			return "toUInt32(intDiv(" + t1 + " * " + stepSize + ", " + sampleRate + "))";
		}
	}

	/** Immutable progress snapshot. */
	static final class Status {
		final String state;
		final String scanId;
		final int partitionsDone;
		final int partitionsTotal;
		final int candidatePairs;
		final int pairs;
		final Instant startedAt;
		final Instant finishedAt;
		final String error;

		Status(String state, String scanId, int partitionsDone, int partitionsTotal, int candidatePairs,
			   int pairs, Instant startedAt, Instant finishedAt, String error) {
			this.state = state;
			this.scanId = scanId;
			this.partitionsDone = partitionsDone;
			this.partitionsTotal = partitionsTotal;
			this.candidatePairs = candidatePairs;
			this.pairs = pairs;
			this.startedAt = startedAt;
			this.finishedAt = finishedAt;
			this.error = error;
		}
	}

	/** A recorded (finished) scan. */
	static final class ScanRecord {
		final String scanId;
		final Instant startedAt;
		final Instant finishedAt;
		final int pairs;

		ScanRecord(String scanId, Instant startedAt, Instant finishedAt, int pairs) {
			this.scanId = scanId;
			this.startedAt = startedAt;
			this.finishedAt = finishedAt;
			this.pairs = pairs;
		}
	}

	/** Catalog details of a track that appears in a duplicate pair. */
	static final class TrackInfo {
		final long resourceId;
		final String path;
		final double duration;
		final String title;
		final String audioUrl;
		final long fingerprints;
		final int eligibleSeconds;

		TrackInfo(long resourceId, String path, double duration, String title, String audioUrl,
				  long fingerprints, int eligibleSeconds) {
			this.resourceId = resourceId;
			this.path = path;
			this.duration = duration;
			this.title = title;
			this.audioUrl = audioUrl;
			this.fingerprints = fingerprints;
			this.eligibleSeconds = eligibleSeconds;
		}
	}

	private static DuplicateScanner instance;

	private final Connections connections;
	private final Object lock = new Object();
	private volatile Status status = new Status("idle", null, 0, 0, 0, 0, null, null, null);
	private boolean schemaReady;

	DuplicateScanner(Connections connections) {
		this.connections = connections;
	}

	static synchronized DuplicateScanner getInstance() {
		if (instance == null) {
			instance = new DuplicateScanner(() -> OlafStorageClickHouse.getInstance().openConnection());
		}
		return instance;
	}

	Status status() {
		return status;
	}

	/** Start a background scan. Returns {@code false} when one is already running. */
	boolean start(Settings settings) {
		synchronized (lock) {
			if ("running".equals(status.state)) return false;
			String scanId = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
					.withZone(ZoneOffset.UTC).format(Instant.now());
			status = new Status("running", scanId, 0, 0, 0, 0, Instant.now(), null, null);
			Thread thread = new Thread(() -> runScan(scanId, settings), "duplicate-scan-" + scanId);
			thread.setDaemon(true);
			thread.start();
			return true;
		}
	}

	private void runScan(String scanId, Settings settings) {
		Instant startedAt = status.startedAt;
		try {
			int pairs = scan(scanId, settings);
			status = new Status("finished", scanId, status.partitionsDone, status.partitionsTotal,
					status.candidatePairs, pairs, startedAt, Instant.now(), null);
			LOG.info("Duplicate scan " + scanId + " finished with " + pairs + " pairs");
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Duplicate scan " + scanId + " failed", e);
			status = new Status("failed", scanId, status.partitionsDone, status.partitionsTotal,
					status.candidatePairs, 0, startedAt, Instant.now(), String.valueOf(e.getMessage()));
		}
	}

	/** Run a complete scan synchronously and return the number of stored pairs. */
	int scan(String scanId, Settings settings) throws SQLException {
		Instant startedAt = Instant.now();
		try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
			ensureSchema(stmt);
			List<long[]> ranges = partitions(stmt, settings);
			DuplicateAccumulator accumulator = settings.newAccumulator();
			for (int i = 0; i < ranges.size(); i++) {
				long[] range = ranges.get(i);
				try (ResultSet rs = stmt.executeQuery(pairSql(settings, range[0], range[1]))) {
					while (rs.next()) {
						accumulator.addPairBucket(rs.getLong("a"), rs.getLong("b"), rs.getInt("bucket"),
								rs.getLong("hits"), ints(rs.getArray("seconds_a")), ints(rs.getArray("seconds_b")));
					}
				}
				try (ResultSet rs = stmt.executeQuery(trackSql(settings, range[0], range[1]))) {
					while (rs.next()) {
						accumulator.addTrack(rs.getLong("r"), rs.getLong("fingerprints"), ints(rs.getArray("seconds")));
					}
				}
				status = new Status("running", scanId, i + 1, ranges.size(), accumulator.pairCount(), 0,
						status.startedAt, null, null);
				LOG.info("Duplicate scan " + scanId + ": partition " + (i + 1) + "/" + ranges.size()
						+ ", candidate pairs " + accumulator.pairCount());
			}
			List<DuplicateAccumulator.Pair> pairs = accumulator.pairs(settings.minSimilarity);
			store(conn, scanId, startedAt, pairs, accumulator);
			return pairs.size();
		}
	}

	private void ensureSchema(Statement stmt) throws SQLException {
		synchronized (lock) {
			if (schemaReady) return;
			// Results must survive restarts and be the same on every replica the API may reach,
			// so the tables follow the replication of olaf_fingerprints.
			boolean replicated;
			try (ResultSet rs = stmt.executeQuery("SELECT engine FROM system.tables "
					+ "WHERE database = currentDatabase() AND name = 'olaf_fingerprints'")) {
				replicated = rs.next() && rs.getString(1).startsWith("Replicated");
			}
			stmt.execute("CREATE TABLE IF NOT EXISTS olaf_duplicate_scans (scan_id String, started_at DateTime, "
					+ "finished_at DateTime, pairs UInt32) ENGINE = " + engine("olaf_duplicate_scans", replicated)
					+ " ORDER BY finished_at");
			stmt.execute("CREATE TABLE IF NOT EXISTS olaf_duplicate_pairs (scan_id String, resource_a Int64, "
					+ "resource_b Int64, offset_seconds Float32, aligned_hits UInt64, coverage_a Float32, "
					+ "coverage_b Float32) ENGINE = " + engine("olaf_duplicate_pairs", replicated)
					+ " ORDER BY (scan_id, resource_a, resource_b)");
			stmt.execute("CREATE TABLE IF NOT EXISTS olaf_duplicate_tracks (scan_id String, resource_id Int64, "
					+ "fingerprints UInt64, eligible_seconds UInt32) ENGINE = " + engine("olaf_duplicate_tracks", replicated)
					+ " ORDER BY (scan_id, resource_id)");
			stmt.execute("CREATE TABLE IF NOT EXISTS olaf_duplicate_deletions (deleted_at DateTime, scan_id String, "
					+ "keep Int64, keep_isrc Nullable(String), resource_id Int64, isrc Nullable(String), "
					+ "similarity Float32, verified_on String, fingerprints_deleted UInt64) ENGINE = "
					+ engine("olaf_duplicate_deletions", replicated) + " ORDER BY (deleted_at, resource_id)");
			schemaReady = true;
		}
	}

	static String engine(String table, boolean replicated) {
		return replicated
				? "ReplicatedMergeTree('/clickhouse/tables/{shard}/" + table + "', '{replica}')"
				: "MergeTree()";
	}

	/** Split the hash range into consecutive [from, to) ranges of roughly equal row count. */
	static List<long[]> partitions(Statement stmt, Settings settings) throws SQLException {
		long rows;
		long min;
		long max;
		try (ResultSet rs = stmt.executeQuery("SELECT count(), min(hash), max(hash) FROM olaf_fingerprints")) {
			rs.next();
			rows = rs.getLong(1);
			min = rs.getLong(2);
			max = rs.getLong(3);
		}
		List<long[]> ranges = new ArrayList<>();
		if (rows == 0) return ranges;
		int count = (int) Math.max(1, (rows + settings.partitionRows - 1) / settings.partitionRows);
		TreeSet<Long> bounds = new TreeSet<>();
		bounds.add(min);
		bounds.add(max + 1);
		if (count > 1) {
			StringBuilder levels = new StringBuilder();
			for (int i = 1; i < count; i++) {
				if (levels.length() > 0) levels.append(',');
				levels.append(String.format(java.util.Locale.ROOT, "%.6f", i / (double) count));
			}
			try (ResultSet rs = stmt.executeQuery("SELECT quantiles(" + levels + ")(hash) FROM olaf_fingerprints")) {
				rs.next();
				for (long bound : longs(rs.getArray(1))) {
					if (bound > min && bound <= max) bounds.add(bound);
				}
			}
		}
		Long previous = null;
		for (long bound : bounds) {
			if (previous != null) ranges.add(new long[]{previous, bound});
			previous = bound;
		}
		return ranges;
	}

	/** Hashes in [from, to) shared by at most {@code maxTracksPerHash} fingerprints. */
	static String eligibleHashesSql(Settings settings, long from, long to) {
		return "SELECT hash FROM olaf_fingerprints WHERE hash >= " + from + " AND hash < " + to
				+ " GROUP BY hash HAVING count() <= " + settings.maxTracksPerHash;
	}

	static String pairSql(Settings settings, long from, long to) {
		return "SELECT p.1 AS a, p.2 AS b, p.3 AS bucket, count() AS hits, "
				+ "groupUniqArray(p.4) AS seconds_a, groupUniqArray(p.5) AS seconds_b "
				+ "FROM (SELECT groupArray(" + settings.maxTracksPerHash
				+ ")((toInt64(f.resource_id), f.t1, m.duration)) AS occ "
				+ "FROM olaf_fingerprints AS f INNER JOIN "
				+ "(SELECT resource_id, duration FROM olaf_metadata FINAL WHERE duration > 0) AS m "
				+ "ON toInt64(f.resource_id) = m.resource_id "
				+ "WHERE f.hash >= " + from + " AND f.hash < " + to
				+ " AND f.hash IN (" + eligibleHashesSql(settings, from, to) + ") "
				+ "GROUP BY f.hash HAVING count() >= 2) "
				+ "ARRAY JOIN arrayFlatten(arrayMap(x -> arrayMap(y -> (x.1, y.1, intDiv(y.2 - x.2 + "
				+ settings.bucketShiftFrames() + ", " + BUCKET_FRAMES + "), "
				+ settings.second("x.2") + ", " + settings.second("y.2") + "), "
				+ "arrayFilter(y -> y.1 > x.1 AND abs(y.2 - x.2) <= " + settings.maxOffsetFrames
				+ " AND abs(y.3 - x.3) <= " + settings.maxDurationDiffSeconds + ", occ)), occ)) AS p "
				+ "GROUP BY a, b, bucket HAVING hits >= " + MIN_PARTITION_HITS
				+ " SETTINGS max_threads = " + settings.maxThreads;
	}

	static String trackSql(Settings settings, long from, long to) {
		return "SELECT toInt64(resource_id) AS r, count() AS fingerprints, "
				+ "groupUniqArrayIf(" + settings.second("t1") + ", hash IN ("
				+ eligibleHashesSql(settings, from, to) + ")) AS seconds "
				+ "FROM olaf_fingerprints WHERE hash >= " + from + " AND hash < " + to
				+ " GROUP BY r SETTINGS max_threads = " + settings.maxThreads;
	}

	private void store(Connection conn, String scanId, Instant startedAt, List<DuplicateAccumulator.Pair> pairs,
					   DuplicateAccumulator accumulator) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO olaf_duplicate_pairs (scan_id, resource_a, "
				+ "resource_b, offset_seconds, aligned_hits, coverage_a, coverage_b) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
			for (DuplicateAccumulator.Pair pair : pairs) {
				ps.setString(1, scanId);
				ps.setLong(2, pair.a);
				ps.setLong(3, pair.b);
				ps.setFloat(4, (float) pair.offsetSeconds);
				ps.setLong(5, pair.alignedHits);
				ps.setFloat(6, (float) pair.coverageA);
				ps.setFloat(7, (float) pair.coverageB);
				ps.addBatch();
			}
			if (!pairs.isEmpty()) ps.executeBatch();
		}
		Set<Long> ids = new HashSet<>();
		for (DuplicateAccumulator.Pair pair : pairs) {
			ids.add(pair.a);
			ids.add(pair.b);
		}
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO olaf_duplicate_tracks (scan_id, resource_id, "
				+ "fingerprints, eligible_seconds) VALUES (?, ?, ?, ?)")) {
			for (long id : ids) {
				DuplicateAccumulator.Track track = accumulator.track(id);
				ps.setString(1, scanId);
				ps.setLong(2, id);
				ps.setLong(3, track.fingerprints);
				ps.setInt(4, track.eligibleSeconds.cardinality());
				ps.addBatch();
			}
			if (!ids.isEmpty()) ps.executeBatch();
		}
		// The scan row makes the results visible, so it is written last.
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO olaf_duplicate_scans (scan_id, started_at, finished_at, pairs) VALUES (?, ?, ?, ?)")) {
			ps.setString(1, scanId);
			ps.setTimestamp(2, Timestamp.from(startedAt));
			ps.setTimestamp(3, Timestamp.from(Instant.now()));
			ps.setInt(4, pairs.size());
			ps.executeUpdate();
		}
		try (PreparedStatement ps = conn.prepareStatement("DELETE FROM olaf_duplicate_pairs WHERE scan_id != ?")) {
			ps.setString(1, scanId);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = conn.prepareStatement("DELETE FROM olaf_duplicate_tracks WHERE scan_id != ?")) {
			ps.setString(1, scanId);
			ps.executeUpdate();
		}
	}

	/** The most recent finished scan, or {@code null} when none exists. */
	ScanRecord latestScan() throws SQLException {
		try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
			ensureSchema(stmt);
			try (ResultSet rs = stmt.executeQuery("SELECT scan_id, started_at, finished_at, pairs "
					+ "FROM olaf_duplicate_scans ORDER BY finished_at DESC LIMIT 1")) {
				if (!rs.next()) return null;
				return new ScanRecord(rs.getString(1), rs.getTimestamp(2).toInstant(),
						rs.getTimestamp(3).toInstant(), rs.getInt(4));
			}
		}
	}

	/** Whether every pair of {@code scan} is visible on the replica this server reads. */
	boolean complete(ScanRecord scan) throws SQLException {
		try (Connection conn = connections.open();
			 PreparedStatement ps = conn.prepareStatement("SELECT count() FROM olaf_duplicate_pairs WHERE scan_id = ?")) {
			ps.setString(1, scan.scanId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getLong(1) >= scan.pairs;
			}
		}
	}

	/** Stored pairs of {@code scan} between {@code keep} and each of {@code others}. */
	List<DuplicateAccumulator.Pair> storedPairs(String scanId, long keep, Collection<Long> others) throws SQLException {
		List<DuplicateAccumulator.Pair> pairs = new ArrayList<>();
		if (others.isEmpty()) return pairs;
		String idList = join(others);
		try (Connection conn = connections.open();
			 PreparedStatement ps = conn.prepareStatement("SELECT resource_a, resource_b, offset_seconds, "
					 + "aligned_hits, coverage_a, coverage_b FROM olaf_duplicate_pairs WHERE scan_id = ? AND "
					 + "((resource_a = ? AND resource_b IN (" + idList + ")) OR "
					 + "(resource_b = ? AND resource_a IN (" + idList + ")))")) {
			ps.setString(1, scanId);
			ps.setLong(2, keep);
			ps.setLong(3, keep);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					pairs.add(new DuplicateAccumulator.Pair(rs.getLong(1), rs.getLong(2), rs.getFloat(3),
							rs.getLong(4), rs.getFloat(5), rs.getFloat(6)));
				}
			}
		}
		return pairs;
	}

	/** One deleted copy, for the deletion log that trackDB alias sync reads. */
	static final class Deletion {
		final Instant deletedAt;
		final String scanId;
		final long keep;
		final String keepIsrc;
		final long resourceId;
		final String isrc;
		final double similarity;
		final String verifiedOn;
		final long fingerprintsDeleted;

		Deletion(Instant deletedAt, String scanId, long keep, String keepIsrc, long resourceId, String isrc,
				 double similarity, String verifiedOn, long fingerprintsDeleted) {
			this.deletedAt = deletedAt;
			this.scanId = scanId;
			this.keep = keep;
			this.keepIsrc = keepIsrc;
			this.resourceId = resourceId;
			this.isrc = isrc;
			this.similarity = similarity;
			this.verifiedOn = verifiedOn;
			this.fingerprintsDeleted = fingerprintsDeleted;
		}
	}

	void logDeletions(List<Deletion> deletions) throws SQLException {
		if (deletions.isEmpty()) return;
		try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
			ensureSchema(stmt);
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO olaf_duplicate_deletions (deleted_at, "
					+ "scan_id, keep, keep_isrc, resource_id, isrc, similarity, verified_on, fingerprints_deleted) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				for (Deletion d : deletions) {
					ps.setTimestamp(1, Timestamp.from(d.deletedAt));
					ps.setString(2, d.scanId == null ? "" : d.scanId);
					ps.setLong(3, d.keep);
					if (d.keepIsrc == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, d.keepIsrc);
					ps.setLong(5, d.resourceId);
					if (d.isrc == null) ps.setNull(6, java.sql.Types.VARCHAR); else ps.setString(6, d.isrc);
					ps.setFloat(7, (float) d.similarity);
					ps.setString(8, d.verifiedOn);
					ps.setLong(9, d.fingerprintsDeleted);
					ps.addBatch();
				}
				ps.executeBatch();
			}
		}
	}

	/** Deletion log, newest first. */
	List<Deletion> deletions(int limit, int offset) throws SQLException {
		List<Deletion> result = new ArrayList<>();
		try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
			ensureSchema(stmt);
			try (ResultSet rs = stmt.executeQuery("SELECT deleted_at, scan_id, keep, keep_isrc, resource_id, isrc, "
					+ "similarity, verified_on, fingerprints_deleted FROM olaf_duplicate_deletions "
					+ "ORDER BY deleted_at DESC, resource_id LIMIT " + limit + " OFFSET " + offset)) {
				while (rs.next()) {
					result.add(new Deletion(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getLong(3),
							rs.getString(4), rs.getLong(5), rs.getString(6), rs.getFloat(7), rs.getString(8),
							rs.getLong(9)));
				}
			}
		}
		return result;
	}

	/** Stored pairs of a scan at or above {@code minSimilarity} whose tracks still exist. */
	List<DuplicateAccumulator.Pair> loadPairs(String scanId, double minSimilarity) throws SQLException {
		List<DuplicateAccumulator.Pair> pairs = new ArrayList<>();
		try (Connection conn = connections.open();
			 PreparedStatement ps = conn.prepareStatement("SELECT resource_a, resource_b, offset_seconds, "
					 + "aligned_hits, coverage_a, coverage_b FROM olaf_duplicate_pairs "
					 + "WHERE scan_id = ? AND least(coverage_a, coverage_b) >= ? "
					 + "AND resource_a IN (SELECT resource_id FROM olaf_metadata) "
					 + "AND resource_b IN (SELECT resource_id FROM olaf_metadata)")) {
			ps.setString(1, scanId);
			ps.setDouble(2, minSimilarity);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					pairs.add(new DuplicateAccumulator.Pair(rs.getLong(1), rs.getLong(2), rs.getFloat(3),
							rs.getLong(4), rs.getFloat(5), rs.getFloat(6)));
				}
			}
		}
		return pairs;
	}

	/** Catalog details for {@code ids}; tracks deleted since the scan are absent. */
	Map<Long, TrackInfo> loadTracks(String scanId, Collection<Long> ids) throws SQLException {
		Map<Long, TrackInfo> tracks = new HashMap<>();
		if (ids.isEmpty()) return tracks;
		String idList = join(ids);
		try (Connection conn = connections.open();
			 PreparedStatement ps = conn.prepareStatement("SELECT m.resource_id, m.path, m.duration, m.title, "
					 + "m.audio_url, t.fingerprints, t.eligible_seconds FROM "
					 + "(SELECT resource_id, path, duration, title, audio_url FROM olaf_metadata FINAL "
					 + "WHERE resource_id IN (" + idList + ")) AS m LEFT JOIN "
					 + "(SELECT resource_id, fingerprints, eligible_seconds FROM olaf_duplicate_tracks "
					 + "WHERE scan_id = ? AND resource_id IN (" + idList + ")) AS t ON m.resource_id = t.resource_id")) {
			ps.setString(1, scanId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long id = rs.getLong(1);
					tracks.put(id, new TrackInfo(id, rs.getString(2), rs.getFloat(3), rs.getString(4),
							rs.getString(5), rs.getLong(6), rs.getInt(7)));
				}
			}
		}
		return tracks;
	}

	/**
	 * Compare {@code keep} with each of {@code others} from current fingerprints,
	 * the same way a scan does but without duration limits. Returns one pair per
	 * other track that shares any aligned hits; a missing pair means no match.
	 */
	List<DuplicateAccumulator.Pair> verify(long keep, Collection<Long> others, Settings settings) throws SQLException {
		Set<Long> ids = new HashSet<>(others);
		ids.add(keep);
		String idList = join(ids);
		Map<Long, List<long[]>> byHash = new HashMap<>();
		try (Connection conn = connections.open(); Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("WITH s AS (SELECT hash, toInt64(resource_id) AS r, t1 "
					 + "FROM olaf_fingerprints WHERE resource_id IN (" + idList + ")) "
					 + "SELECT hash, r, t1 FROM s WHERE hash IN (SELECT hash FROM olaf_fingerprints "
					 + "WHERE hash IN (SELECT hash FROM s) GROUP BY hash HAVING count() <= "
					 + settings.maxTracksPerHash + ") SETTINGS max_threads = " + settings.maxThreads)) {
			while (rs.next()) {
				byHash.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new long[]{rs.getLong(2), rs.getLong(3)});
			}
		}
		return comparePrints(keep, byHash, settings);
	}

	/** Score {@code keep} against every other track in fingerprint rows grouped by hash ({resource, t1}). */
	static List<DuplicateAccumulator.Pair> comparePrints(long keep, Map<Long, List<long[]>> byHash, Settings settings) {
		DuplicateAccumulator accumulator = settings.newAccumulator();
		double fps = settings.framesPerSecond();
		for (List<long[]> rows : byHash.values()) {
			for (long[] row : rows) {
				accumulator.addTrack(row[0], 1, new int[]{(int) (row[1] / fps)});
			}
			for (long[] kept : rows) {
				if (kept[0] != keep) continue;
				for (long[] other : rows) {
					if (other[0] == keep) continue;
					long[] a = keep < other[0] ? kept : other;
					long[] b = keep < other[0] ? other : kept;
					long offset = b[1] - a[1];
					if (Math.abs(offset) > settings.maxOffsetFrames) continue;
					int bucket = (int) Math.floorDiv(offset + settings.bucketShiftFrames(), (long) BUCKET_FRAMES);
					accumulator.addPairBucket(a[0], b[0], bucket, 1,
							new int[]{(int) (a[1] / fps)}, new int[]{(int) (b[1] / fps)});
				}
			}
		}
		return accumulator.pairs(0);
	}

	private static String join(Collection<Long> ids) {
		StringBuilder sb = new StringBuilder();
		for (long id : ids) {
			if (sb.length() > 0) sb.append(',');
			sb.append(id);
		}
		return sb.toString();
	}

	static int[] ints(Array array) throws SQLException {
		long[] values = longs(array);
		int[] result = new int[values.length];
		for (int i = 0; i < values.length; i++) result[i] = Math.toIntExact(values[i]);
		return result;
	}

	/** JDBC drivers return ClickHouse arrays as primitive or boxed arrays depending on the element type. */
	static long[] longs(Array array) throws SQLException {
		Object value = array.getArray();
		if (value instanceof long[]) return (long[]) value;
		if (value instanceof int[]) {
			int[] source = (int[]) value;
			long[] result = new long[source.length];
			for (int i = 0; i < source.length; i++) result[i] = source[i];
			return result;
		}
		if (value instanceof short[]) {
			short[] source = (short[]) value;
			long[] result = new long[source.length];
			for (int i = 0; i < source.length; i++) result[i] = source[i];
			return result;
		}
		Object[] source = (Object[]) value;
		long[] result = new long[source.length];
		for (int i = 0; i < source.length; i++) result[i] = ((Number) source[i]).longValue();
		return result;
	}
}
