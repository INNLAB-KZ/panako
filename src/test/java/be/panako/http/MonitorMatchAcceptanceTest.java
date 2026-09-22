package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.util.Config;
import be.panako.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorMatchAcceptanceTest {

	@AfterEach
	void resetThresholds() {
		Config.set(Key.MONITOR_MIN_SCORE, "20");
		Config.set(Key.MONITOR_MIN_PERCENTAGE, "0.5");
	}

	@Test
	void acceptsTrackWhenStrongWindowWouldBeDilutedByWeakOverlap() {
		Config.set(Key.MONITOR_MIN_SCORE, "20");
		Config.set(Key.MONITOR_MIN_PERCENTAGE, "0.5");

		String json = MonitorHandler.buildResponseJson(null, List.of(
				result(0, 42, 84, 0.6),
				result(30, 60, 85, 0.2)), null, 100);

		assertTrue(json.contains("\"unique_tracks_count\":1"));
	}

	@Test
	void rejectsTrackWhenEveryWindowIsBelowThreshold() {
		Config.set(Key.MONITOR_MIN_SCORE, "20");
		Config.set(Key.MONITOR_MIN_PERCENTAGE, "0.5");

		String json = MonitorHandler.buildResponseJson(null, List.of(
				result(0, 42, 84, 0.4),
				result(30, 60, 85, 0.2)), null, 100);

		assertTrue(json.contains("\"unique_tracks_count\":0"));
	}

	private static QueryResult result(double start, double stop, double score, double percentage) {
		return new QueryResult(
				"query.wav", start, stop,
				"QT6642509124.m4a", "1000075177",
				34.3, 64.1,
				score, 1.0, 1.0, percentage);
	}
}
