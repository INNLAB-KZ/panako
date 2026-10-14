package be.panako.strategy.olaf.storage;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OlafCompactQueryStorageTest {
    @Test void primitiveIndexMatchesHashMapWithOverlapDuplicatesAndOverflow() {
        Map<Long, Long> reference = new HashMap<>();
        OlafQueryHashIndex index = new OlafQueryHashIndex();
        Random random = new Random(147);
        List<Long> hashes = new ArrayList<>(Arrays.asList(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE));
        for (int i = 0; i < 20000; i++) hashes.add(i % 2 == 0 ? random.nextLong() : (long) random.nextInt(100));
        for (long hash : hashes) {
            for (int delta = -2; delta <= 2; delta++) {
                reference.put(hash + delta, hash);
                index.put(hash + delta, hash);
            }
        }
        Set<Long> enumerated = new HashSet<>();
        for (int i = 0; i < index.slots(); i++) if (index.occupied(i)) {
            long key = index.keyAt(i);
            assertTrue(enumerated.add(key), "Each SQL hash must be emitted exactly once");
            assertEquals(reference.get(key).longValue(), index.originalAt(i));
        }
        assertEquals(reference.keySet(), enumerated);
        assertArrayEquals(reference.keySet().stream().mapToLong(Long::longValue).sorted().toArray(), index.sortedKeys());
        reference.forEach((key, value) -> assertEquals(value.longValue(), index.originalAt(index.find(key))));
        assertEquals(-1, index.find(123456789123456789L));
    }

    @Test void packedHitsRetainEveryFieldDuplicateAndOrderAcrossBlocks() {
        OlafPackedHitList list = new OlafPackedHitList(Long.MIN_VALUE);
        List<OlafHit> reference = new ArrayList<>();
        Random random = new Random(47);
        for (int i = 0; i < 30000; i++) {
            OlafHit hit = new OlafHit(Long.MIN_VALUE, random.nextLong(), random.nextInt(), random.nextInt());
            reference.add(hit);
            list.append(hit.matchedNearHash, hit.t, hit.resourceID);
        }
        reference.add(reference.get(0));
        list.add(reference.get(0));
        assertEquals(reference.size(), list.size());
        for (int i = 0; i < list.size(); i++) same(reference.get(i), list.get(i));
        var iterator = list.listIterator();
        for (OlafHit expected : reference) {
            same(expected, iterator.next());
            iterator.set(null); // Existing drainHits contract, including iterator validity.
        }
        assertFalse(iterator.hasNext());
        for (OlafHit hit : list) assertNull(hit);
        assertNull(list.set(4088, reference.get(4088)));
        same(reference.get(4088), list.get(4088));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(list.size()));
    }

    private static void same(OlafHit expected, OlafHit actual) {
        assertEquals(expected.originalHash, actual.originalHash);
        assertEquals(expected.matchedNearHash, actual.matchedNearHash);
        assertEquals(expected.t, actual.t);
        assertEquals(expected.resourceID, actual.resourceID);
    }
}
