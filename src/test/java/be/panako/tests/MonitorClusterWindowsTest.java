package be.panako.tests;

import be.panako.http.MonitorHandler;
import be.panako.strategy.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MonitorHandler#clusterWindows} and
 * {@link MonitorHandler#effectiveGapThreshold}.
 *
 * <p>The clustering function is the core fix for the regression where a track
 * playing twice on a recording (with a gap between occurrences) was collapsed
 * into a single fictitious match whose envelope spanned both plays — sometimes
 * exceeding the track's own duration by an order of magnitude.</p>
 */
public class MonitorClusterWindowsTest {

    private static QueryResult win(double start, double end) {
        return new QueryResult(
                "query.wav", start, end,
                "track.mp3", "trk-1",
                0.0, end - start,
                100.0, 1.0, 1.0, 1.0);
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertEquals(0, MonitorHandler.clusterWindows(Collections.emptyList(), 30.0).size());
        assertEquals(0, MonitorHandler.clusterWindows(null, 30.0).size());
    }

    @Test
    void singleWindowYieldsSingleCluster() {
        List<QueryResult> input = List.of(win(10, 40));
        List<List<QueryResult>> clusters = MonitorHandler.clusterWindows(input, 30.0);
        assertEquals(1, clusters.size());
        assertEquals(1, clusters.get(0).size());
    }

    @Test
    void overlappingWindowsStayInOneCluster() {
        // Pass 1 emits overlapping detections; they all belong to one play.
        List<QueryResult> input = Arrays.asList(
                win(0, 30), win(25, 55), win(50, 80), win(75, 105));
        List<List<QueryResult>> clusters = MonitorHandler.clusterWindows(input, 30.0);
        assertEquals(1, clusters.size());
        assertEquals(4, clusters.get(0).size());
    }

    @Test
    void twoOccurrencesSeparatedByLargeGapSplitIntoTwoClusters() {
        // Mirrors the production "Miles Away" case: track plays 17:44–17:52,
        // then again 19:54–22:17 — a ~2 minute gap should split clusters.
        List<QueryResult> input = Arrays.asList(
                win(1064, 1072),                          // first occurrence (8s)
                win(1194, 1224), win(1219, 1249),         // second occurrence
                win(1244, 1274), win(1300, 1336));
        List<List<QueryResult>> clusters = MonitorHandler.clusterWindows(input, 30.0);
        assertEquals(2, clusters.size());
        assertEquals(1, clusters.get(0).size());
        assertEquals(4, clusters.get(1).size());

        // Sanity: each cluster's envelope is shorter than the original gap-spanning envelope.
        double c0Span = envelopeEnd(clusters.get(0)) - envelopeStart(clusters.get(0));
        double c1Span = envelopeEnd(clusters.get(1)) - envelopeStart(clusters.get(1));
        assertTrue(c0Span < 200);
        assertTrue(c1Span < 200);
    }

    @Test
    void gapExactlyAtThresholdStaysInSameCluster() {
        // gap of 30s with threshold=30s should NOT split (strictly greater is required).
        List<QueryResult> input = Arrays.asList(win(0, 30), win(60, 90));
        List<List<QueryResult>> clusters = MonitorHandler.clusterWindows(input, 30.0);
        assertEquals(1, clusters.size());
    }

    @Test
    void gapJustOverThresholdSplits() {
        List<QueryResult> input = Arrays.asList(win(0, 30), win(60.01, 90));
        List<List<QueryResult>> clusters = MonitorHandler.clusterWindows(input, 30.0);
        assertEquals(2, clusters.size());
    }

    @Test
    void unsortedInputIsHandledByCallerNotByClusterer() {
        // The contract is that the caller sorts; the function relies on order.
        // Verify that with sorted input we get the expected behavior.
        List<QueryResult> input = new ArrayList<>(Arrays.asList(
                win(100, 130), win(0, 30), win(200, 230)));
        input.sort((a, b) -> Double.compare(a.queryStart, b.queryStart));
        List<List<QueryResult>> clusters = MonitorHandler.clusterWindows(input, 30.0);
        assertEquals(3, clusters.size());
    }

    @Test
    void effectiveGapThresholdCapsByHalfTrackDuration() {
        // Short track — half its duration is smaller than maxGap, so cap kicks in.
        assertEquals(20.0, MonitorHandler.effectiveGapThreshold(30.0, 40.0), 1e-9);

        // Long track — half its duration exceeds maxGap, so maxGap wins.
        assertEquals(30.0, MonitorHandler.effectiveGapThreshold(30.0, 200.0), 1e-9);

        // Unknown duration — fall back to maxGap.
        assertEquals(30.0, MonitorHandler.effectiveGapThreshold(30.0, -1.0), 1e-9);
        assertEquals(30.0, MonitorHandler.effectiveGapThreshold(30.0, 0.0), 1e-9);
    }

    private static double envelopeStart(List<QueryResult> cluster) {
        double s = Double.POSITIVE_INFINITY;
        for (QueryResult r : cluster) if (r.queryStart < s) s = r.queryStart;
        return s;
    }

    private static double envelopeEnd(List<QueryResult> cluster) {
        double e = Double.NEGATIVE_INFINITY;
        for (QueryResult r : cluster) if (r.queryStop > e) e = r.queryStop;
        return e;
    }
}
