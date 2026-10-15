package be.panako.http;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges per-partition duplicate-scan rows into scored track pairs.
 *
 * <p>Each partition reports, per track pair and time-offset bucket, the aligned
 * hash hits and the seconds of both tracks that those hits cover. Each
 * partition also reports, per track, the seconds that have at least one
 * eligible (not too common) hash. A pair's similarity for one track is the
 * fraction of that track's eligible seconds covered by aligned hits around the
 * pair's strongest offset.</p>
 */
final class DuplicateAccumulator {

	/** Final score of one track pair. Track {@code a} always has the smaller id. */
	static final class Pair {
		final long a;
		final long b;
		final double offsetSeconds;
		final long alignedHits;
		final double coverageA;
		final double coverageB;

		Pair(long a, long b, double offsetSeconds, long alignedHits, double coverageA, double coverageB) {
			this.a = a;
			this.b = b;
			this.offsetSeconds = offsetSeconds;
			this.alignedHits = alignedHits;
			this.coverageA = coverageA;
			this.coverageB = coverageB;
		}

		/** Both tracks must be covered, so the weaker coverage decides. */
		double similarity() {
			return Math.min(coverageA, coverageB);
		}
	}

	/** Per-track totals across all partitions. */
	static final class Track {
		long fingerprints;
		final BitSet eligibleSeconds = new BitSet();
	}

	private static final class Bucket {
		long hits;
		final BitSet secondsA = new BitSet();
		final BitSet secondsB = new BitSet();
	}

	private final int bucketFrames;
	private final int bucketShiftFrames;
	private final double framesPerSecond;
	private final Map<Long, Map<Long, Map<Integer, Bucket>>> pairs = new HashMap<>();
	private final Map<Long, Track> tracks = new HashMap<>();

	/**
	 * @param bucketFrames width of one offset bucket in fingerprint frames
	 * @param bucketShiftFrames frames added to an offset before bucketing, so buckets are non-negative
	 * @param framesPerSecond fingerprint frames per second
	 */
	DuplicateAccumulator(int bucketFrames, int bucketShiftFrames, double framesPerSecond) {
		this.bucketFrames = bucketFrames;
		this.bucketShiftFrames = bucketShiftFrames;
		this.framesPerSecond = framesPerSecond;
	}

	void addPairBucket(long a, long b, int bucket, long hits, int[] secondsA, int[] secondsB) {
		if (a > b) {
			throw new IllegalArgumentException("Pair ids must be ordered: " + a + " > " + b);
		}
		Bucket target = pairs.computeIfAbsent(a, k -> new HashMap<>())
				.computeIfAbsent(b, k -> new HashMap<>())
				.computeIfAbsent(bucket, k -> new Bucket());
		target.hits += hits;
		for (int second : secondsA) target.secondsA.set(second);
		for (int second : secondsB) target.secondsB.set(second);
	}

	void addTrack(long resourceId, long fingerprints, int[] eligibleSeconds) {
		Track track = tracks.computeIfAbsent(resourceId, k -> new Track());
		track.fingerprints += fingerprints;
		for (int second : eligibleSeconds) track.eligibleSeconds.set(second);
	}

	Track track(long resourceId) {
		return tracks.get(resourceId);
	}

	int pairCount() {
		int count = 0;
		for (Map<Long, Map<Integer, Bucket>> byB : pairs.values()) count += byB.size();
		return count;
	}

	/** Score every pair at its strongest offset and keep those at or above {@code minSimilarity}. */
	List<Pair> pairs(double minSimilarity) {
		List<Pair> result = new ArrayList<>();
		for (Map.Entry<Long, Map<Long, Map<Integer, Bucket>>> byA : pairs.entrySet()) {
			for (Map.Entry<Long, Map<Integer, Bucket>> byB : byA.getValue().entrySet()) {
				Pair pair = score(byA.getKey(), byB.getKey(), byB.getValue());
				if (pair != null && pair.similarity() >= minSimilarity) result.add(pair);
			}
		}
		return result;
	}

	private Pair score(long a, long b, Map<Integer, Bucket> buckets) {
		Track trackA = tracks.get(a);
		Track trackB = tracks.get(b);
		if (trackA == null || trackB == null) return null;
		int eligibleA = trackA.eligibleSeconds.cardinality();
		int eligibleB = trackB.eligibleSeconds.cardinality();
		if (eligibleA == 0 || eligibleB == 0) return null;

		int peak = 0;
		long peakHits = -1;
		for (Map.Entry<Integer, Bucket> entry : buckets.entrySet()) {
			long hits = entry.getValue().hits;
			if (hits > peakHits || (hits == peakHits && entry.getKey() < peak)) {
				peak = entry.getKey();
				peakHits = hits;
			}
		}
		// Encoder jitter spreads one alignment over neighbouring buckets.
		long hits = 0;
		BitSet coveredA = new BitSet();
		BitSet coveredB = new BitSet();
		for (int bucket = peak - 1; bucket <= peak + 1; bucket++) {
			Bucket neighbour = buckets.get(bucket);
			if (neighbour == null) continue;
			hits += neighbour.hits;
			coveredA.or(neighbour.secondsA);
			coveredB.or(neighbour.secondsB);
		}
		coveredA.and(trackA.eligibleSeconds);
		coveredB.and(trackB.eligibleSeconds);
		double offsetFrames = peak * (double) bucketFrames - bucketShiftFrames + bucketFrames / 2.0;
		return new Pair(a, b, offsetFrames / framesPerSecond, hits,
				coveredA.cardinality() / (double) eligibleA,
				coveredB.cardinality() / (double) eligibleB);
	}
}
