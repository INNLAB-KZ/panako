package be.panako.strategy.olaf.storage;

import be.panako.util.Config;
import be.panako.util.Key;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ClickHouse-backed storage for OLAF fingerprints.
 *
 * <p>Schema (auto-created on first use):</p>
 * <pre>
 * CREATE TABLE olaf_fingerprints (
 *     hash       Int64,
 *     resource_id Int32,
 *     t1         Int32
 * ) ENGINE = MergeTree()
 * ORDER BY hash;
 *
 * CREATE TABLE olaf_metadata (
 *     resource_id Int64,
 *     path        String,
 *     duration    Float32,
 *     num_fingerprints Int32,
 *     title       Nullable(String),
 *     audio_url   Nullable(String)
 * ) ENGINE = ReplacingMergeTree()
 * ORDER BY resource_id;
 * </pre>
 *
 * <p>Pre-existing production tables created before {@code title} and
 * {@code audio_url} were introduced must be migrated once by running:</p>
 * <pre>
 * ALTER TABLE olaf_metadata ADD COLUMN IF NOT EXISTS title Nullable(String);
 * ALTER TABLE olaf_metadata ADD COLUMN IF NOT EXISTS audio_url Nullable(String);
 * </pre>
 */
public class OlafStorageClickHouse implements OlafStorage {

	private static final Logger LOG = Logger.getLogger(OlafStorageClickHouse.class.getName());

	private static OlafStorageClickHouse instance;

	private final String jdbcUrl;
	private final List<long[]> storeQueue = new ArrayList<>();
	private final ThreadLocal<List<Long>> queryQueue = ThreadLocal.withInitial(ArrayList::new);
	private final List<long[]> deleteQueue = new ArrayList<>();

	private OlafStorageClickHouse() {
		this.jdbcUrl = Config.get(Key.OLAF_CLICKHOUSE_URL);
		initSchema();
	}

	public static synchronized OlafStorageClickHouse getInstance() {
		if (instance == null) {
			instance = new OlafStorageClickHouse();
		}
		return instance;
	}

	private Connection getConnection() throws SQLException {
		return DriverManager.getConnection(jdbcUrl);
	}

	private void initSchema() {
		try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS olaf_fingerprints (" +
					"hash Int64, " +
					"resource_id Int32, " +
					"t1 Int32" +
					") ENGINE = MergeTree() " +
					"ORDER BY hash " +
					"SETTINGS index_granularity = 8192");

			stmt.execute("CREATE TABLE IF NOT EXISTS olaf_metadata (" +
					"resource_id Int64, " +
					"path String, " +
					"duration Float32, " +
					"num_fingerprints Int32, " +
					"title Nullable(String), " +
					"audio_url Nullable(String)" +
					") ENGINE = ReplacingMergeTree() " +
					"ORDER BY resource_id");

			LOG.info("ClickHouse schema initialized at " + jdbcUrl);
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to initialize ClickHouse schema", e);
			throw new RuntimeException("ClickHouse init failed: " + e.getMessage(), e);
		}
	}

	// ===================== STORE =====================

	@Override
	public void storeMetadata(long resourceID, String resourcePath, float duration, int numberOfFingerprints) {
		storeMetadataExt(resourceID, resourcePath, duration, numberOfFingerprints, null, null);
	}

	/**
	 * Store metadata including the optional descriptive {@code title} and
	 * {@code audio_url} fields. Either of those fields may be {@code null}.
	 *
	 * <p>Uses the same {@code INSERT} statement as {@link #storeMetadata}; the
	 * underlying {@code ReplacingMergeTree} engine deduplicates rows by
	 * {@code resource_id} on background merge, so calling this method after a
	 * previous {@code storeMetadata} (e.g. one issued by upstream strategy
	 * code) safely overwrites the existing entry.</p>
	 */
	public void storeMetadataExt(long resourceID, String resourcePath, float duration, int numberOfFingerprints,
								 String title, String audioUrl) {
		try (Connection conn = getConnection();
			 PreparedStatement ps = conn.prepareStatement(
					 "INSERT INTO olaf_metadata (resource_id, path, duration, num_fingerprints, title, audio_url) VALUES (?, ?, ?, ?, ?, ?)")) {
			ps.setLong(1, resourceID);
			ps.setString(2, resourcePath);
			ps.setFloat(3, duration);
			ps.setInt(4, numberOfFingerprints);
			if (title == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, title);
			if (audioUrl == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, audioUrl);
			ps.executeUpdate();
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to store metadata", e);
		}
	}

	/**
	 * Update only the {@code path} column of an existing metadata row, identified
	 * by {@code resourceID}. All other columns (duration, num_fingerprints, title,
	 * audio_url) are preserved by re-inserting them. The {@code ReplacingMergeTree}
	 * engine collapses the duplicate row on the next background merge so the
	 * latest insert wins.
	 *
	 * <p>Returns {@code true} when the row was found and a new version with the
	 * updated path was written; {@code false} when no row exists for the given
	 * {@code resourceID} (no-op).</p>
	 *
	 * <p>The underlying fingerprints in {@code olaf_fingerprints} are not touched
	 * — only the descriptive {@code path} label changes.</p>
	 */
	public boolean updateMetadataPath(long resourceID, String newPath) {
		try (Connection conn = getConnection();
			 PreparedStatement select = conn.prepareStatement(
					 "SELECT duration, num_fingerprints, title, audio_url FROM olaf_metadata FINAL WHERE resource_id = ? LIMIT 1")) {
			select.setLong(1, resourceID);
			try (ResultSet rs = select.executeQuery()) {
				if (!rs.next()) return false;
				float duration = rs.getFloat(1);
				int fpCount = rs.getInt(2);
				String title = rs.getString(3);
				if (rs.wasNull()) title = null;
				String audioUrl = rs.getString(4);
				if (rs.wasNull()) audioUrl = null;
				storeMetadataExt(resourceID, newPath, duration, fpCount, title, audioUrl);
				return true;
			}
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to update metadata path for resource_id=" + resourceID, e);
			return false;
		}
	}

	/**
	 * Delete all rows for {@code resourceID} from both {@code olaf_fingerprints}
	 * and {@code olaf_metadata}. Uses ClickHouse lightweight {@code DELETE} which
	 * marks rows for removal and is replicated through Keeper to every replica.
	 *
	 * <p>Returns the row count that existed prior to deletion (sum of fingerprint
	 * rows + metadata rows). Returns {@code -1} on SQL error.</p>
	 */
	public long deleteByResourceId(long resourceID) {
		try (Connection conn = getConnection()) {
			long fpCount;
			long metaCount;
			try (PreparedStatement count = conn.prepareStatement(
					"SELECT count() FROM olaf_fingerprints WHERE resource_id = ?")) {
				count.setLong(1, resourceID);
				try (ResultSet rs = count.executeQuery()) {
					fpCount = rs.next() ? rs.getLong(1) : 0;
				}
			}
			try (PreparedStatement count = conn.prepareStatement(
					"SELECT count() FROM olaf_metadata FINAL WHERE resource_id = ?")) {
				count.setLong(1, resourceID);
				try (ResultSet rs = count.executeQuery()) {
					metaCount = rs.next() ? rs.getLong(1) : 0;
				}
			}
			try (PreparedStatement del = conn.prepareStatement(
					"DELETE FROM olaf_fingerprints WHERE resource_id = ?")) {
				del.setLong(1, resourceID);
				del.executeUpdate();
			}
			try (PreparedStatement del = conn.prepareStatement(
					"DELETE FROM olaf_metadata WHERE resource_id = ?")) {
				del.setLong(1, resourceID);
				del.executeUpdate();
			}
			return fpCount + metaCount;
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to delete resource_id=" + resourceID, e);
			return -1;
		}
	}

	@Override
	public void addToStoreQueue(long fingerprintHash, int resourceIdentifier, int t1) {
		storeQueue.add(new long[]{fingerprintHash, resourceIdentifier, t1});
	}

	@Override
	public void processStoreQueue() {
		if (storeQueue.isEmpty()) return;

		try (Connection conn = getConnection();
			 PreparedStatement ps = conn.prepareStatement(
					 "INSERT INTO olaf_fingerprints (hash, resource_id, t1) VALUES (?, ?, ?)")) {
			for (long[] entry : storeQueue) {
				ps.setLong(1, entry[0]);
				ps.setInt(2, (int) entry[1]);
				ps.setInt(3, (int) entry[2]);
				ps.addBatch();
			}
			ps.executeBatch();
			LOG.info("Stored " + storeQueue.size() + " fingerprints to ClickHouse");
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to process store queue (" + storeQueue.size() + " items)", e);
		} finally {
			storeQueue.clear();
		}
	}

	@Override
	public void clearStoreQueue() {
		storeQueue.clear();
	}

	// ===================== QUERY =====================

	@Override
	public void addToQueryQueue(long queryHash) {
		queryQueue.get().add(queryHash);
	}

	@Override
	public void processQueryQueue(Map<Long, List<OlafHit>> matchAccumulator, int range, Set<Integer> resourcesToAvoid) {
		List<Long> queue = queryQueue.get();
		if (queue.isEmpty()) return;

		try (Connection conn = getConnection()) {
			// Build IN clause with hash range expansion
			Set<Long> expandedHashes = new HashSet<>();
			Map<Long, Long> nearToOriginal = new HashMap<>();
			for (long queryHash : queue) {
				for (int delta = -range; delta <= range; delta++) {
					long nearHash = queryHash + delta;
					expandedHashes.add(nearHash);
					nearToOriginal.put(nearHash, queryHash);
				}
			}

			// Query in batches to avoid too-large IN clauses.
			// 10000 caused 4-5M read_rows per query (bloom filter FP rate explodes with big IN sets);
			// 1000 keeps bloom effective and brings p99 from ~18s down to ~2s.
			List<Long> hashList = new ArrayList<>(expandedHashes);
			int batchSize = 1000;

			for (int i = 0; i < hashList.size(); i += batchSize) {
				List<Long> batch = hashList.subList(i, Math.min(i + batchSize, hashList.size()));

				StringBuilder sql = new StringBuilder("SELECT hash, resource_id, t1 FROM olaf_fingerprints WHERE hash IN (");
				for (int j = 0; j < batch.size(); j++) {
					if (j > 0) sql.append(",");
					sql.append(batch.get(j));
				}
				sql.append(")");

				try (Statement stmt = conn.createStatement();
					 ResultSet rs = stmt.executeQuery(sql.toString())) {
					while (rs.next()) {
						long dbHash = rs.getLong("hash");
						int resourceID = rs.getInt("resource_id");
						int t1 = rs.getInt("t1");

						if (resourcesToAvoid.contains(resourceID)) continue;

						Long originalHash = nearToOriginal.get(dbHash);
						if (originalHash == null) continue;

						OlafHit hit = new OlafHit(originalHash, dbHash, t1, resourceID);
						matchAccumulator.computeIfAbsent(originalHash, k -> new ArrayList<>()).add(hit);
					}
				}
			}
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to process query queue", e);
		} finally {
			queue.clear();
		}
	}

	// ===================== DELETE =====================

	@Override
	public void addToDeleteQueue(long fingerprintHash, int resourceIdentifier, int t1) {
		deleteQueue.add(new long[]{fingerprintHash, resourceIdentifier, t1});
	}

	@Override
	public void processDeleteQueue() {
		if (deleteQueue.isEmpty()) return;

		// Collect resource IDs to delete
		Set<Integer> resourceIds = new HashSet<>();
		for (long[] entry : deleteQueue) {
			resourceIds.add((int) entry[1]);
		}

		try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
			for (int resourceId : resourceIds) {
				stmt.execute("ALTER TABLE olaf_fingerprints DELETE WHERE resource_id = " + resourceId);
			}
			LOG.info("Deleted fingerprints for " + resourceIds.size() + " resources from ClickHouse");
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to process delete queue", e);
		} finally {
			deleteQueue.clear();
		}
	}

	@Override
	public void deleteMetadata(long resourceID) {
		try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("ALTER TABLE olaf_metadata DELETE WHERE resource_id = " + resourceID);
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to delete metadata for " + resourceID, e);
		}
	}

	// ===================== METADATA =====================

	@Override
	public OlafResourceMetadata getMetadata(long identifier) {
		OlafResourceMetadataExt ext = getMetadataExt(identifier);
		return ext == null ? null : ext.base;
	}

	/**
	 * Read the full metadata row, including the optional {@code title} and
	 * {@code audio_url} fields. Returns {@code null} when no row exists.
	 */
	public OlafResourceMetadataExt getMetadataExt(long identifier) {
		try (Connection conn = getConnection();
			 PreparedStatement ps = conn.prepareStatement(
					 "SELECT resource_id, path, duration, num_fingerprints, title, audio_url FROM olaf_metadata FINAL WHERE resource_id = ?")) {
			ps.setLong(1, identifier);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					OlafResourceMetadata meta = new OlafResourceMetadata();
					meta.identifier = rs.getInt("resource_id");
					meta.path = rs.getString("path");
					meta.duration = rs.getFloat("duration");
					meta.numFingerprints = rs.getInt("num_fingerprints");
					String title = rs.getString("title");
					if (rs.wasNull()) title = null;
					String audioUrl = rs.getString("audio_url");
					if (rs.wasNull()) audioUrl = null;
					return new OlafResourceMetadataExt(meta, title, audioUrl);
				}
			}
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to get metadata for " + identifier, e);
		}
		return null;
	}

	// ===================== STATS =====================

	@Override
	public void printStatistics(boolean printDetailedStats) {
		try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery("SELECT count() FROM olaf_fingerprints");
			if (rs.next()) System.out.println("Fingerprints: " + rs.getLong(1));
			rs.close();

			rs = stmt.executeQuery("SELECT count() FROM olaf_metadata FINAL");
			if (rs.next()) System.out.println("Audio items: " + rs.getLong(1));
			rs.close();
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to print statistics", e);
		}
	}

	@Override
	public void clear() {
		try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("TRUNCATE TABLE olaf_fingerprints");
			stmt.execute("TRUNCATE TABLE olaf_metadata");
			LOG.info("ClickHouse tables cleared");
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to clear tables", e);
		}
	}
}
