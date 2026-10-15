package be.panako.strategy.olaf;

import be.panako.strategy.olaf.storage.OlafHit;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OlafHitConversionTest {
    @Test
    void primitiveBucketCachePreservesCollisionOrderExistingListsAndHitIdentity() {
        Map<Integer, List<OlafMatch>> expected = new HashMap<>(), actual = new HashMap<>();
        List<OlafMatch> existing = new ArrayList<>();
        expected.put(0, new ArrayList<>());
        actual.put(0, existing);
        expected.put(-1, null);
        actual.put(-1, null);
        OlafMatchBuckets cache = new OlafMatchBuckets(actual);
        Random random = new Random(20261012);
        for (int i = 0; i < 40000; i++) {
            int id = i % 5 == 0 ? Integer.MIN_VALUE
                    : i % 5 == 1 ? 0
                    : i % 5 == 2 ? -1
                    : i % 5 == 3 ? (128 + random.nextInt(3000)) * 65537
                    : random.nextInt();
            OlafMatch hit = new OlafMatch();
            hit.identifier = id;
            hit.queryTime = i;
            expected.computeIfAbsent(id, ignored -> new ArrayList<>()).add(hit);
            List<OlafMatch> bucket = cache.get(id);
            assertSame(actual.get(id), bucket);
            bucket.add(hit);
        }
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
        assertEquals(expected, actual);
        assertSame(existing, actual.get(0));
    }

    @Test
    void conversionPreservesDuplicatesFieldsAndOrderWhileReleasingSourceHits() {
        Map<Long, List<OlafHit>> source = new LinkedHashMap<>();
        var bucket = new ArrayList<>(List.of(
                new OlafHit(100, 101, 17, 7), new OlafHit(100, 101, 17, 7),
                new OlafHit(100, 102, 19, 8)));
        source.put(100L, bucket);
        source.put(200L, new ArrayList<>(List.of(new OlafHit(200, 201, 29, 7))));
        Map<Integer, List<OlafMatch>> result = new HashMap<>();
        OlafStrategy.drainHits(source, Map.of(
                100L, new OlafFingerprint(100L, 3), 200L, new OlafFingerprint(200L, 5)), result);
        assertTrue(source.isEmpty());
        assertTrue(bucket.stream().allMatch(Objects::isNull));
        assertEquals(3, result.get(7).size());
        assertEquals(1, result.get(8).size());
        var first = result.get(7).get(0);
        assertEquals(7, first.identifier);
        assertEquals(17, first.matchTime);
        assertEquals(3, first.queryTime);
        assertEquals(100L, first.originalHash);
        assertEquals(101L, first.matchedNearHash);
        assertEquals(5, result.get(7).get(2).queryTime);
        assertEquals(29, result.get(7).get(2).matchTime);
    }
}
