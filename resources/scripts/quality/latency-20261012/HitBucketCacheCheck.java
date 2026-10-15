package be.panako.strategy.olaf;

import java.util.*;

/** Check list identity/order and HashMap iteration against the original operation. */
public final class HitBucketCacheCheck {
    public static void main(String[] args) {
        Random random = new Random(20261012);
        for (int run = 0; run < 40; run++) {
            Map<Integer, List<OlafMatch>> expected = new HashMap<>(), actual = new HashMap<>();
            List<OlafMatch> existing = new ArrayList<>();
            expected.put(0, new ArrayList<>()); actual.put(0, existing);
            expected.put(-1, null); actual.put(-1, null);
            OlafMatchBuckets cache = new OlafMatchBuckets(actual);
            for (int i = 0; i < 20000; i++) {
                int id;
                switch (i % 7) {
                    case 0: id = (random.nextInt(3000) + 128) * 65537; break;
                    case 1: id = Integer.MIN_VALUE; break;
                    case 2: id = Integer.MAX_VALUE; break;
                    case 3: id = 0; break;
                    case 4: id = -1; break;
                    case 5: id = random.nextInt(1000) - 500; break;
                    default: id = random.nextInt();
                }
                OlafMatch match = new OlafMatch(); match.identifier = id; match.queryTime = i;
                expected.computeIfAbsent(id, ignored -> new ArrayList<>()).add(match);
                List<OlafMatch> bucket = cache.get(id);
                if (bucket != actual.get(id)) throw new AssertionError("Backing list identity changed");
                bucket.add(match);
            }
            if (!new ArrayList<>(expected.keySet()).equals(new ArrayList<>(actual.keySet()))) {
                throw new AssertionError("Map iteration order changed");
            }
            if (!expected.equals(actual)) throw new AssertionError("Hit order/content changed");
            if (actual.get(0) != existing) throw new AssertionError("Existing list replaced");
        }
        System.out.println("PASS: 800000 hits; growth, collisions, ties, signed IDs, existing/null buckets and iteration order");
    }
}
