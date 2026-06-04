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
 *     num_fingerprints Int32
 * ) ENGINE = ReplacingMergeTree()
 * ORDER BY resource_id;
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
					"num_fingerprints Int32" +
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
		try (Connection conn = getConnection();
			 PreparedStatement ps = conn.prepareStatement(
					 "INSERT INTO olaf_metadata (resource_id, path, duration, num_fingerprints) VALUES (?, ?, ?, ?)")) {
			ps.setLong(1, resourceID);
			ps.setString(2, resourcePath);
			ps.setFloat(3, duration);
			ps.setInt(4, numberOfFingerprints);
			ps.executeUpdate();
		} catch (SQLException e) {
			LOG.log(Level.SEVERE, "Failed to store metadata", e);
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

			// Query in batches to avoid too-large IN clauses
			List<Long> hashList = new ArrayList<>(expandedHashes);
			int batchSize = 10000;

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
		try (Connection conn = getConnection();
			 PreparedStatement ps = conn.prepareStatement(
					 "SELECT resource_id, path, duration, num_fingerprints FROM olaf_metadata FINAL WHERE resource_id = ?")) {
			ps.setLong(1, identifier);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					OlafResourceMetadata meta = new OlafResourceMetadata();
					meta.identifier = rs.getInt("resource_id");
					meta.path = rs.getString("path");
					meta.duration = rs.getFloat("duration");
					meta.numFingerprints = rs.getInt("num_fingerprints");
					return meta;
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
