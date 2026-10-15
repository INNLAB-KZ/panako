package be.panako.http;

import be.panako.strategy.Strategy;
import be.panako.strategy.olaf.storage.OlafStorageClickHouse;
import be.panako.util.Config;
import be.panako.util.Key;

/** Remove incomplete stores without decoding long audio again. */
final class StoreResourceCleanup {
	private StoreResourceCleanup() {}

	static void delete(Strategy strategy, String filePath, int identifier) {
		if (Config.get(Key.STRATEGY).equalsIgnoreCase("OLAF")
				&& Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE")) {
			if (OlafStorageClickHouse.getInstance().deleteByResourceId(identifier) < 0) {
				throw new IllegalStateException("Could not delete incomplete fingerprints for " + filePath);
			}
		} else {
			strategy.delete(filePath);
		}
	}
}
