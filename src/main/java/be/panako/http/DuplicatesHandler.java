package be.panako.http;

import be.panako.strategy.olaf.storage.OlafResourceMetadataExt;
import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duplicate reference tracks (OLAF on ClickHouse only).
 *
 * <ul>
 *   <li>{@code POST /api/v1/duplicates/scan} starts a background scan of all fingerprints.
 *       When a finished scan exists, it is refused unless the body is {@code {"force": true}}.</li>
 *   <li>{@code GET /api/v1/duplicates/scan} reports scan progress and the last finished scan.</li>
 *   <li>{@code GET /api/v1/duplicates?min_similarity=0.95&limit=100&offset=0} lists groups
 *       of similar tracks from the last finished scan, with quality metrics and a
 *       recommended copy to keep.</li>
 *   <li>{@code POST /api/v1/duplicates/delete} with
 *       {@code {"keep": 1, "delete": [2, 3], "dry_run": true, "min_similarity": 0.5}}
 *       checks each track against {@code keep} in the stored scan (or, with
 *       {@code "verify": true}, on current fingerprints) and, unless
 *       {@code dry_run}, deletes the tracks and logs them. Nothing is deleted
 *       when any track is below {@code min_similarity}.</li>
 *   <li>{@code GET /api/v1/duplicates/deletions?limit=100&offset=0} lists deleted
 *       copies with the ISRC kept in their place, for trackDB aliases.</li>
 * </ul>
 */
public class DuplicatesHandler implements HttpHandler {

	private static final Logger LOG = Logger.getLogger(DuplicatesHandler.class.getName());
	private static final String PREFIX = "/api/v1/duplicates";
	private static final int MAX_DELETE_IDS = 100;

	private final ReentrantLock writeLock;

	public DuplicatesHandler(ReentrantLock writeLock) {
		this.writeLock = writeLock;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF")
					|| !Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
				HttpUtil.sendError(exchange, 400, "Duplicate detection requires STRATEGY=OLAF and OLAF_STORAGE=CLICKHOUSE");
				return;
			}
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
			if (path.equals(PREFIX) || path.equals(PREFIX + "/")) {
				if (!method.equals("GET")) { HttpUtil.sendError(exchange, 405, "Method not allowed"); return; }
				list(exchange);
			} else if (path.equals(PREFIX + "/scan")) {
				if (method.equals("POST")) startScan(exchange);
				else if (method.equals("GET")) scanStatus(exchange);
				else HttpUtil.sendError(exchange, 405, "Method not allowed");
			} else if (path.equals(PREFIX + "/deletions")) {
				if (!method.equals("GET")) { HttpUtil.sendError(exchange, 405, "Method not allowed"); return; }
				deletions(exchange);
			} else if (path.equals(PREFIX + "/delete")) {
				if (!method.equals("POST")) { HttpUtil.sendError(exchange, 405, "Method not allowed"); return; }
				delete(exchange);
			} else {
				HttpUtil.sendError(exchange, 404, "Not found");
			}
		} catch (IllegalArgumentException e) {
			HttpUtil.sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Duplicates request failed", e);
			HttpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	private void startScan(HttpExchange exchange) throws Exception {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		boolean force = Boolean.TRUE.equals(jsonBoolean(body, "force"));
		DuplicateScanner scanner = DuplicateScanner.getInstance();
		DuplicateScanner.ScanRecord last = scanner.latestScan();
		// One scan is enough: results are stored, and a rescan reads the whole fingerprint table again.
		if (last != null && !force) {
			HttpUtil.sendJson(exchange, 409, "{\"status\":\"error\",\"message\":\"A finished scan exists; "
					+ "use GET " + PREFIX + ". Send {\\\"force\\\": true} to replace it.\",\"last_finished_scan\":"
					+ scanJson(last) + "}");
			return;
		}
		if (!scanner.start(DuplicateScanner.Settings.fromConfig())) {
			HttpUtil.sendJson(exchange, 409, statusJson(scanner.status(), last));
			return;
		}
		HttpUtil.sendJson(exchange, 202, statusJson(scanner.status(), last));
	}

	private void scanStatus(HttpExchange exchange) throws Exception {
		DuplicateScanner scanner = DuplicateScanner.getInstance();
		HttpUtil.sendJson(exchange, 200, statusJson(scanner.status(), scanner.latestScan()));
	}

	static String statusJson(DuplicateScanner.Status status, DuplicateScanner.ScanRecord last) {
		StringBuilder json = new StringBuilder("{\"status\":\"").append(status.state).append('"');
		if (status.scanId != null) {
			json.append(",\"scan_id\":\"").append(status.scanId).append('"')
					.append(",\"partitions_done\":").append(status.partitionsDone)
					.append(",\"partitions_total\":").append(status.partitionsTotal)
					.append(",\"candidate_pairs\":").append(status.candidatePairs)
					.append(",\"pairs\":").append(status.pairs)
					.append(",\"started_at\":").append(time(status.startedAt))
					.append(",\"finished_at\":").append(time(status.finishedAt));
			if (status.error != null) {
				json.append(",\"error\":\"").append(HttpUtil.escapeJson(status.error)).append('"');
			}
		}
		if (last != null) json.append(",\"last_finished_scan\":").append(scanJson(last));
		return json.append('}').toString();
	}

	static String scanJson(DuplicateScanner.ScanRecord scan) {
		return "{\"scan_id\":\"" + scan.scanId + "\",\"started_at\":" + time(scan.startedAt)
				+ ",\"finished_at\":" + time(scan.finishedAt) + ",\"pairs\":" + scan.pairs + "}";
	}

	private void list(HttpExchange exchange) throws Exception {
		Map<String, String> params = HttpUtil.parseQueryParams(exchange);
		double minSimilarity = fraction(params.get("min_similarity"), Config.getFloat(Key.DUPLICATE_SCAN_MIN_SIMILARITY));
		int limit = integer(params.get("limit"), 100, 1, 1000, "limit");
		int offset = integer(params.get("offset"), 0, 0, Integer.MAX_VALUE, "offset");

		DuplicateScanner scanner = DuplicateScanner.getInstance();
		DuplicateScanner.ScanRecord scan = scanner.latestScan();
		if (scan == null) {
			HttpUtil.sendError(exchange, 404, "No finished duplicate scan; start one with POST " + PREFIX + "/scan");
			return;
		}
		if (!scanner.complete(scan)) {
			HttpUtil.sendError(exchange, 503, "Scan " + scan.scanId + " is still replicating to this ClickHouse node; retry later");
			return;
		}
		List<DuplicateGroups.Group> groups = DuplicateGroups.build(scanner.loadPairs(scan.scanId, minSimilarity));
		List<DuplicateGroups.Group> page = groups.subList(Math.min(offset, groups.size()),
				Math.min(offset + limit, groups.size()));
		Set<Long> ids = new LinkedHashSet<>();
		for (DuplicateGroups.Group group : page) ids.addAll(group.members);
		Map<Long, DuplicateScanner.TrackInfo> tracks = scanner.loadTracks(scan.scanId, ids);
		HttpUtil.sendJson(exchange, 200, listJson(scan, minSimilarity, Config.getFloat(Key.DUPLICATE_MIN_SIMILARITY),
				groups.size(), offset, page, tracks));
	}

	static String listJson(DuplicateScanner.ScanRecord scan, double minSimilarity, double duplicateSimilarity,
						   int totalGroups, int offset, List<DuplicateGroups.Group> page,
						   Map<Long, DuplicateScanner.TrackInfo> tracks) {
		StringBuilder json = new StringBuilder("{\"status\":\"ok\"")
				.append(",\"scan_id\":\"").append(scan.scanId).append('"')
				.append(",\"scan_finished_at\":").append(time(scan.finishedAt))
				.append(",\"min_similarity\":").append(fmt(minSimilarity))
				.append(",\"duplicate_similarity\":").append(fmt(duplicateSimilarity))
				.append(",\"total_groups\":").append(totalGroups)
				.append(",\"offset\":").append(offset)
				.append(",\"groups\":[");
		for (int i = 0; i < page.size(); i++) {
			DuplicateGroups.Group group = page.get(i);
			long keep = DuplicateGroups.recommend(group, tracks);
			if (i > 0) json.append(',');
			json.append("{\"label\":\"").append(label(group.minSimilarity(), duplicateSimilarity)).append('"')
					.append(",\"max_similarity\":").append(fmt(group.maxSimilarity()))
					.append(",\"min_similarity\":").append(fmt(group.minSimilarity()))
					.append(",\"recommended_keep\":").append(keep)
					.append(",\"tracks\":[");
			boolean first = true;
			for (long id : group.members) {
				DuplicateScanner.TrackInfo track = tracks.get(id);
				if (track == null) continue;
				if (!first) json.append(',');
				first = false;
				json.append("{\"resource_id\":").append(id)
						.append(",\"isrc\":").append(string(HttpUtil.extractIsrc(track.path)))
						.append(",\"path\":").append(string(track.path))
						.append(",\"title\":").append(string(track.title))
						.append(",\"audio_url\":").append(string(track.audioUrl))
						.append(",\"duration\":").append(HttpUtil.formatOneDecimal(track.duration))
						.append(",\"fingerprints\":").append(track.fingerprints)
						.append(",\"fingerprints_per_second\":").append(HttpUtil.formatOneDecimal(DuplicateGroups.density(track)))
						.append(",\"mean_similarity\":").append(fmt(group.meanSimilarity(id)))
						.append(",\"recommended\":").append(id == keep)
						.append('}');
			}
			json.append("],\"pairs\":[");
			for (int p = 0; p < group.pairs.size(); p++) {
				if (p > 0) json.append(',');
				appendPair(json, group.pairs.get(p), duplicateSimilarity);
			}
			json.append("]}");
		}
		return json.append("]}").toString();
	}

	private static void appendPair(StringBuilder json, DuplicateAccumulator.Pair pair, double duplicateSimilarity) {
		json.append("{\"resource_a\":").append(pair.a)
				.append(",\"resource_b\":").append(pair.b)
				.append(",\"label\":\"").append(label(pair.similarity(), duplicateSimilarity)).append('"')
				.append(",\"similarity\":").append(fmt(pair.similarity()))
				.append(",\"coverage_a\":").append(fmt(pair.coverageA))
				.append(",\"coverage_b\":").append(fmt(pair.coverageB))
				.append(",\"offset_seconds\":").append(String.format(Locale.ROOT, "%.3f", pair.offsetSeconds))
				.append(",\"aligned_hits\":").append(pair.alignedHits)
				.append('}');
	}

	private void delete(HttpExchange exchange) throws Exception {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Long keep = jsonLong(body, "keep");
		List<Long> remove = jsonLongArray(body, "delete");
		if (keep == null) throw new IllegalArgumentException("Missing 'keep'");
		if (remove == null || remove.isEmpty()) throw new IllegalArgumentException("Missing 'delete' list");
		Set<Long> ids = new LinkedHashSet<>(remove);
		if (ids.contains(keep)) throw new IllegalArgumentException("'delete' must not contain 'keep'");
		if (ids.size() > MAX_DELETE_IDS) throw new IllegalArgumentException("At most " + MAX_DELETE_IDS + " ids per request");
		Boolean dryRunValue = jsonBoolean(body, "dry_run");
		boolean dryRun = dryRunValue == null || dryRunValue;
		String similarityText = jsonNumber(body, "min_similarity");
		double minSimilarity = fraction(similarityText, Config.getFloat(Key.DUPLICATE_SCAN_MIN_SIMILARITY));

		OlafStorageClickHouse storage = OlafStorageClickHouse.getInstance();
		if (storage.getMetadataExt(keep) == null) {
			HttpUtil.sendError(exchange, 404, "Track to keep not found: " + keep);
			return;
		}
		Map<Long, OlafResourceMetadataExt> metadata = new HashMap<>();
		for (long id : ids) {
			OlafResourceMetadataExt meta = storage.getMetadataExt(id);
			if (meta == null) {
				HttpUtil.sendError(exchange, 404, "Track to delete not found: " + id);
				return;
			}
			metadata.put(id, meta);
		}

		// By default the stored scan decides, so deleting needs no new fingerprint reads.
		// "verify": true compares current fingerprints instead (about 10 s per request).
		boolean recheck = Boolean.TRUE.equals(jsonBoolean(body, "verify"));
		DuplicateScanner scanner = DuplicateScanner.getInstance();
		DuplicateScanner.ScanRecord scan = scanner.latestScan();
		if (!recheck && scan == null) {
			HttpUtil.sendError(exchange, 404, "No finished duplicate scan; send \"verify\": true or run a scan");
			return;
		}
		if (!recheck && !scanner.complete(scan)) {
			HttpUtil.sendError(exchange, 503, "Scan " + scan.scanId + " is still replicating to this ClickHouse node; retry later");
			return;
		}
		List<DuplicateAccumulator.Pair> found = recheck
				? scanner.verify(keep, ids, DuplicateScanner.Settings.fromConfig())
				: scanner.storedPairs(scan.scanId, keep, ids);
		Map<Long, DuplicateAccumulator.Pair> verified = new HashMap<>();
		for (DuplicateAccumulator.Pair pair : found) {
			verified.put(pair.a == keep ? pair.b : pair.a, pair);
		}
		boolean allVerified = true;
		for (long id : ids) {
			DuplicateAccumulator.Pair pair = verified.get(id);
			if (pair == null || pair.similarity() < minSimilarity) allVerified = false;
		}
		String verifiedOn = recheck ? "fingerprints" : "scan:" + scan.scanId;

		Map<Long, Long> deleted = null;
		if (allVerified && !dryRun) {
			writeLock.lock();
			try {
				deleted = storage.deleteByResourceIds(ids);
			} finally {
				writeLock.unlock();
			}
			LOG.info("Deleted duplicates of " + keep + ": " + ids);
			OlafResourceMetadataExt kept = storage.getMetadataExt(keep);
			String keepIsrc = kept == null ? null : HttpUtil.extractIsrc(kept.base.path);
			List<DuplicateScanner.Deletion> log = new ArrayList<>();
			Instant now = Instant.now();
			for (long id : ids) {
				log.add(new DuplicateScanner.Deletion(now, scan == null ? null : scan.scanId, keep, keepIsrc, id,
						HttpUtil.extractIsrc(metadata.get(id).base.path), verified.get(id).similarity(), verifiedOn,
						deleted.getOrDefault(id, 0L)));
			}
			try {
				scanner.logDeletions(log);
			} catch (Exception e) {
				// The tracks are already gone; the response still lists them for trackDB.
				LOG.log(Level.SEVERE, "Deleted " + ids + " but could not write the deletion log", e);
			}
		}
		String status = !allVerified ? "rejected" : dryRun ? "dry_run" : "ok";
		HttpUtil.sendJson(exchange, allVerified ? 200 : 409,
				deleteJson(status, keep, minSimilarity, verifiedOn, ids, verified, metadata, deleted));
	}

	private void deletions(HttpExchange exchange) throws Exception {
		Map<String, String> params = HttpUtil.parseQueryParams(exchange);
		int limit = integer(params.get("limit"), 100, 1, 10000, "limit");
		int offset = integer(params.get("offset"), 0, 0, Integer.MAX_VALUE, "offset");
		StringBuilder json = new StringBuilder("{\"status\":\"ok\",\"offset\":").append(offset).append(",\"deletions\":[");
		boolean first = true;
		for (DuplicateScanner.Deletion d : DuplicateScanner.getInstance().deletions(limit, offset)) {
			if (!first) json.append(',');
			first = false;
			json.append("{\"deleted_at\":").append(time(d.deletedAt))
					.append(",\"keep\":").append(d.keep)
					.append(",\"keep_isrc\":").append(string(d.keepIsrc))
					.append(",\"resource_id\":").append(d.resourceId)
					.append(",\"isrc\":").append(string(d.isrc))
					.append(",\"similarity\":").append(fmt(d.similarity))
					.append(",\"verified_on\":").append(string(d.verifiedOn))
					.append(",\"fingerprints_deleted\":").append(d.fingerprintsDeleted)
					.append('}');
		}
		HttpUtil.sendJson(exchange, 200, json.append("]}").toString());
	}

	static String deleteJson(String status, long keep, double minSimilarity, String verifiedOn, Set<Long> ids,
							 Map<Long, DuplicateAccumulator.Pair> verified, Map<Long, OlafResourceMetadataExt> metadata,
							 Map<Long, Long> deleted) {
		StringBuilder json = new StringBuilder("{\"status\":\"").append(status).append('"')
				.append(",\"keep\":").append(keep)
				.append(",\"min_similarity\":").append(fmt(minSimilarity))
				.append(",\"verified_on\":").append(string(verifiedOn))
				.append(",\"tracks\":[");
		boolean first = true;
		for (long id : ids) {
			DuplicateAccumulator.Pair pair = verified.get(id);
			double similarity = pair == null ? 0 : pair.similarity();
			if (!first) json.append(',');
			first = false;
			OlafResourceMetadataExt meta = metadata.get(id);
			json.append("{\"resource_id\":").append(id)
					.append(",\"isrc\":").append(string(meta == null ? null : HttpUtil.extractIsrc(meta.base.path)))
					.append(",\"similarity\":").append(fmt(similarity))
					.append(",\"verified\":").append(similarity >= minSimilarity);
			if (pair != null) {
				boolean keepIsA = pair.a == keep;
				json.append(",\"coverage_keep\":").append(fmt(keepIsA ? pair.coverageA : pair.coverageB))
						.append(",\"coverage_track\":").append(fmt(keepIsA ? pair.coverageB : pair.coverageA))
						.append(",\"offset_seconds\":")
						.append(String.format(Locale.ROOT, "%.3f", keepIsA ? pair.offsetSeconds : -pair.offsetSeconds))
						.append(",\"aligned_hits\":").append(pair.alignedHits);
			}
			json.append(",\"deleted\":").append(deleted != null);
			if (deleted != null) json.append(",\"fingerprints_deleted\":").append(deleted.getOrDefault(id, 0L));
			json.append('}');
		}
		return json.append("]}").toString();
	}

	static String label(double similarity, double duplicateSimilarity) {
		return similarity >= duplicateSimilarity ? "duplicate" : "similar";
	}

	private static String fmt(double fraction) {
		return String.format(Locale.ROOT, "%.3f", fraction);
	}

	private static String time(Instant instant) {
		return instant == null ? "null" : "\"" + instant + "\"";
	}

	private static String string(String value) {
		return value == null ? "null" : "\"" + HttpUtil.escapeJson(value) + "\"";
	}

	static double fraction(String text, double defaultValue) {
		if (text == null || text.isEmpty()) return defaultValue;
		double value;
		try {
			value = Double.parseDouble(text);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("min_similarity must be a number between 0 and 1");
		}
		if (!(value >= 0 && value <= 1)) throw new IllegalArgumentException("min_similarity must be between 0 and 1");
		return value;
	}

	private static int integer(String text, int defaultValue, int min, int max, String name) {
		if (text == null || text.isEmpty()) return defaultValue;
		try {
			int value = Integer.parseInt(text);
			if (value >= min && value <= max) return value;
		} catch (NumberFormatException ignored) {
			// reported below
		}
		throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max);
	}

	private static Matcher field(String json, String key, String valuePattern) {
		Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(" + valuePattern + ")").matcher(json);
		return matcher.find() ? matcher : null;
	}

	static String jsonNumber(String json, String key) {
		Matcher matcher = field(json, key, "-?[0-9][0-9.eE+-]*");
		return matcher == null ? null : matcher.group(1);
	}

	static Long jsonLong(String json, String key) {
		Matcher matcher = field(json, key, "-?[0-9]+");
		return matcher == null ? null : Long.parseLong(matcher.group(1));
	}

	static Boolean jsonBoolean(String json, String key) {
		Matcher matcher = field(json, key, "true|false");
		return matcher == null ? null : Boolean.valueOf(matcher.group(1));
	}

	static List<Long> jsonLongArray(String json, String key) {
		Matcher matcher = field(json, key, "\\[[^\\]]*\\]");
		if (matcher == null) return null;
		String inner = matcher.group(1);
		inner = inner.substring(1, inner.length() - 1).trim();
		List<Long> values = new ArrayList<>();
		if (inner.isEmpty()) return values;
		for (String part : inner.split(",")) {
			String value = part.trim();
			if (!value.matches("-?[0-9]+")) throw new IllegalArgumentException("'" + key + "' must hold resource ids");
			values.add(Long.parseLong(value));
		}
		return values;
	}
}
