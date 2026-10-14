package be.panako.strategy.olaf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Primitive lookup cache for one hit-conversion pass; backing map retains its ordering. */
final class OlafMatchBuckets {
    private final Map<Integer, List<OlafMatch>> backing;
    private int[] identifiers = new int[256];
    private List<OlafMatch>[] buckets = newBuckets(256);
    private int size;

    OlafMatchBuckets(Map<Integer, List<OlafMatch>> backing) {
        this.backing = backing;
    }

    List<OlafMatch> get(int identifier) {
        int slot = slot(identifier, identifiers, buckets);
        if (buckets[slot] != null) return buckets[slot];
        if (size >= buckets.length / 2) {
            grow();
            slot = slot(identifier, identifiers, buckets);
        }
        // Use the same operation on first encounter as the original conversion.
        // HashMap's bin/tree insertion and tie-breaking iteration stay intact.
        List<OlafMatch> bucket = backing.computeIfAbsent(identifier, ignored -> new ArrayList<>());
        identifiers[slot] = identifier;
        buckets[slot] = bucket;
        size++;
        return bucket;
    }

    private void grow() {
        int[] nextIdentifiers = new int[identifiers.length * 2];
        List<OlafMatch>[] nextBuckets = newBuckets(nextIdentifiers.length);
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] == null) continue;
            int slot = slot(identifiers[i], nextIdentifiers, nextBuckets);
            nextIdentifiers[slot] = identifiers[i];
            nextBuckets[slot] = buckets[i];
        }
        identifiers = nextIdentifiers;
        buckets = nextBuckets;
    }

    private static int slot(int identifier, int[] identifiers, List<OlafMatch>[] buckets) {
        int hash = identifier;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        int slot = hash & (buckets.length - 1);
        while (buckets[slot] != null && identifiers[slot] != identifier) {
            slot = (slot + 1) & (buckets.length - 1);
        }
        return slot;
    }

    @SuppressWarnings("unchecked")
    private static List<OlafMatch>[] newBuckets(int size) {
        return (List<OlafMatch>[]) new List<?>[size];
    }
}
