package be.panako.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Groups duplicate pairs into sets of tracks and picks the copy to keep. */
final class DuplicateGroups {

	static final class Group {
		final List<Long> members;
		final List<DuplicateAccumulator.Pair> pairs;

		Group(List<Long> members, List<DuplicateAccumulator.Pair> pairs) {
			this.members = members;
			this.pairs = pairs;
		}

		double maxSimilarity() {
			double max = 0;
			for (DuplicateAccumulator.Pair pair : pairs) max = Math.max(max, pair.similarity());
			return max;
		}

		double minSimilarity() {
			double min = 1;
			for (DuplicateAccumulator.Pair pair : pairs) min = Math.min(min, pair.similarity());
			return min;
		}

		/**
		 * Mean similarity of {@code id} to the other members. A member pair that
		 * was not found counts as zero, so a track linked through others ranks lower.
		 */
		double meanSimilarity(long id) {
			double sum = 0;
			for (DuplicateAccumulator.Pair pair : pairs) {
				if (pair.a == id || pair.b == id) sum += pair.similarity();
			}
			return members.size() < 2 ? 0 : sum / (members.size() - 1);
		}
	}

	private DuplicateGroups() {}

	/**
	 * Connected components of the pair graph, strongest groups first. Members
	 * are sorted by id; groups by highest pair similarity, then size, then first id.
	 */
	static List<Group> build(List<DuplicateAccumulator.Pair> pairs) {
		Map<Long, Long> parent = new HashMap<>();
		for (DuplicateAccumulator.Pair pair : pairs) union(parent, pair.a, pair.b);
		Map<Long, List<Long>> members = new TreeMap<>();
		for (Long id : parent.keySet()) members.computeIfAbsent(find(parent, id), k -> new ArrayList<>()).add(id);
		Map<Long, List<DuplicateAccumulator.Pair>> groupPairs = new HashMap<>();
		for (DuplicateAccumulator.Pair pair : pairs) {
			groupPairs.computeIfAbsent(find(parent, pair.a), k -> new ArrayList<>()).add(pair);
		}
		List<Group> groups = new ArrayList<>();
		for (Map.Entry<Long, List<Long>> entry : members.entrySet()) {
			List<Long> ids = entry.getValue();
			ids.sort(null);
			List<DuplicateAccumulator.Pair> list = groupPairs.get(entry.getKey());
			list.sort(Comparator.comparingDouble(DuplicateAccumulator.Pair::similarity).reversed()
					.thenComparingLong(p -> p.a).thenComparingLong(p -> p.b));
			groups.add(new Group(ids, list));
		}
		groups.sort(Comparator.comparingDouble(Group::maxSimilarity).reversed()
				.thenComparing(g -> -g.members.size())
				.thenComparingLong(g -> g.members.get(0)));
		return groups;
	}

	/**
	 * The member best suited as the single reference: the copy most similar to
	 * the others (it represents the group's broadcasts best), then the one with
	 * more fingerprints per second (more hashes to match in noisy audio), then
	 * the longer one, then the smaller id.
	 */
	static long recommend(Group group, Map<Long, DuplicateScanner.TrackInfo> tracks) {
		Long best = null;
		for (long id : group.members) {
			if (!tracks.containsKey(id)) continue;
			if (best == null || compare(group, tracks, id, best) > 0) best = id;
		}
		return best == null ? group.members.get(0) : best;
	}

	private static int compare(Group group, Map<Long, DuplicateScanner.TrackInfo> tracks, long x, long y) {
		int bySimilarity = Double.compare(round(group.meanSimilarity(x)), round(group.meanSimilarity(y)));
		if (bySimilarity != 0) return bySimilarity;
		int byDensity = Double.compare(round(density(tracks.get(x))), round(density(tracks.get(y))));
		if (byDensity != 0) return byDensity;
		int byDuration = Double.compare(tracks.get(x).duration, tracks.get(y).duration);
		if (byDuration != 0) return byDuration;
		return Long.compare(y, x);
	}

	static double density(DuplicateScanner.TrackInfo track) {
		return track.duration > 0 ? track.fingerprints / track.duration : 0;
	}

	/** Ignore differences below measurement noise. */
	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}

	private static long find(Map<Long, Long> parent, long id) {
		long root = id;
		while (parent.get(root) != root) root = parent.get(root);
		while (parent.get(id) != root) {
			long next = parent.get(id);
			parent.put(id, root);
			id = next;
		}
		return root;
	}

	private static void union(Map<Long, Long> parent, long a, long b) {
		parent.putIfAbsent(a, a);
		parent.putIfAbsent(b, b);
		long rootA = find(parent, a);
		long rootB = find(parent, b);
		if (rootA != rootB) parent.put(Math.max(rootA, rootB), Math.min(rootA, rootB));
	}
}
