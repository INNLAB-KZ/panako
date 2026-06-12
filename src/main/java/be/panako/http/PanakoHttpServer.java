package be.panako.http;

import be.panako.strategy.Strategy;
import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import be.tarsos.dsp.io.PipeDecoder;
import be.tarsos.dsp.io.PipedAudioStream;

/**
 * Embedded HTTP server for the Panako acoustic fingerprinting API.
 * Uses com.sun.net.httpserver.HttpServer (built into the JDK).
 *
 * <p>A ReentrantLock serializes store/delete operations because the underlying
 * LMDB storage layer uses plain HashMap for per-thread queues (not thread-safe)
 * and LMDB only allows a single write transaction at a time.
 * Query and stats requests run concurrently without the lock.</p>
 *
 * <p>This class has its own main() so the HTTP server can be launched independently
 * without modifying any upstream Panako code.</p>
 */
public class PanakoHttpServer {

	private static final Logger LOG = Logger.getLogger(PanakoHttpServer.class.getName());

	private static final int DEFAULT_PORT = 8080;
	private static final int DEFAULT_THREAD_POOL_SIZE = 10;
	private static final int DEFAULT_MAX_UPLOAD_SIZE_MB = 100;

	private final int port;
	private final int maxUploadSizeMB;
	private final Strategy strategy;
	private final HttpServer server;
	private final ExecutorService executor;
	private final ReentrantLock writeLock;

	/**
	 * Returns true if the current storage backend supports concurrent writes (e.g. ClickHouse).
	 */
	private static boolean isStorageConcurrent() {
		String strategy = Config.get(Key.STRATEGY).toUpperCase();
		if (strategy.equals("OLAF")) {
			return Config.get(Key.OLAF_STORAGE).equalsIgnoreCase("CLICKHOUSE");
		} else if (strategy.equals("PANAKO")) {
			return Config.get(Key.PANAKO_STORAGE).equalsIgnoreCase("CLICKHOUSE");
		}
		return false;
	}

	/**
	 * A ReentrantLock that does nothing — used when storage supports concurrent writes.
	 */
	private static class NoOpLock extends ReentrantLock {
		@Override public void lock() {}
		@Override public void unlock() {}
		@Override public boolean isHeldByCurrentThread() { return false; }
	}

	/**
	 * Create a new HTTP server.
	 * @param port the port to listen on
	 * @param threadPoolSize the number of handler threads
	 * @param maxUploadSizeMB max upload size in MB
	 * @throws IOException if the server socket cannot be opened
	 */
	public PanakoHttpServer(int port, int threadPoolSize, int maxUploadSizeMB) throws IOException {
		this.port = port;
		this.maxUploadSizeMB = maxUploadSizeMB;
		this.writeLock = isStorageConcurrent() ? new NoOpLock() : new ReentrantLock();

		this.strategy = Strategy.getInstance();
		this.executor = Executors.newFixedThreadPool(threadPoolSize);
		this.server = HttpServer.create(new InetSocketAddress(port), 0);
		this.server.setExecutor(executor);

		// Register endpoints (health is public, everything else requires API key)
		server.createContext("/api/v1/health", new HealthHandler());
		server.createContext("/api/v1/stats", new ApiKeyFilter(new StatsHandler(strategy)));
		server.createContext("/api/v1/store/fingerprints", new ApiKeyFilter(new StoreFingerprintsHandler(writeLock)));
		server.createContext("/api/v1/store/rename", new ApiKeyFilter(new RenameMetadataHandler()));
		server.createContext("/api/v1/store/url", new ApiKeyFilter(new StoreUrlHandler(strategy, writeLock, maxUploadSizeMB)));
		server.createContext("/api/v1/store", new ApiKeyFilter(new StoreHandler(strategy, writeLock, maxUploadSizeMB)));
		server.createContext("/api/v1/query/fingerprints", new ApiKeyFilter(new QueryFingerprintsHandler()));
		server.createContext("/api/v1/query", new ApiKeyFilter(new QueryHandler(strategy, maxUploadSizeMB)));
		server.createContext("/api/v1/monitor/url", new ApiKeyFilter(new MonitorUrlHandler(strategy, maxUploadSizeMB)));
		server.createContext("/api/v1/monitor", new ApiKeyFilter(new MonitorHandler(strategy, maxUploadSizeMB)));
		server.createContext("/api/v1/delete/by_id", new ApiKeyFilter(new DeleteByIdHandler()));
		server.createContext("/api/v1/delete", new ApiKeyFilter(new DeleteHandler(strategy, writeLock, maxUploadSizeMB)));

		LOG.info(String.format("Panako HTTP server configured on port %d with %d threads", port, threadPoolSize));
	}

	/**
	 * Start the server. This method blocks until the JVM shuts down.
	 */
	public void start() {
		server.start();
		String strategyName = Config.get(Key.STRATEGY);
		System.out.printf("Panako HTTP API server started on port %d (strategy: %s)%n", port, strategyName);
		System.out.printf("  POST /api/v1/store       — store audio fingerprints (multipart)%n");
		System.out.printf("  POST /api/v1/store/url   — store audio from URL (JSON)%n");
		System.out.printf("  POST /api/v1/store/fingerprints — store pre-computed fingerprints (JSON)%n");
		System.out.printf("  POST /api/v1/store/rename — rename metadata path for an indexed track (JSON)%n");
		System.out.printf("  POST /api/v1/query       — query for matches%n");
		System.out.printf("  POST /api/v1/query/fingerprints — query with pre-computed fingerprints (JSON)%n");
		System.out.printf("  POST /api/v1/monitor     — monitor long audio for multiple matches (multipart)%n");
		System.out.printf("  POST /api/v1/monitor/url — monitor audio from URL (JSON)%n");
		System.out.printf("  POST /api/v1/delete      — delete fingerprints%n");
		System.out.printf("  POST /api/v1/delete/by_id — delete fingerprints by resource_id (JSON)%n");
		System.out.printf("  GET  /api/v1/stats       — database statistics%n");
		System.out.printf("  GET  /api/v1/health      — health check%n");

		// Add shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("Shutting down Panako HTTP server...");
			server.stop(2);
			executor.shutdown();
		}));

		// Block the main thread
		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Stop the server.
	 */
	public void stop() {
		server.stop(2);
		executor.shutdown();
	}

	/**
	 * Get an integer from system property, environment variable, or default.
	 * Checks: -Dkey=value, then env KEY, then default.
	 */
	private static int getIntConfig(String name, int defaultValue) {
		String val = System.getProperty(name);
		if (val == null) val = System.getenv(name.toUpperCase());
		if (val == null) val = System.getenv(name);
		if (val != null) {
			try { return Integer.parseInt(val.trim()); } catch (NumberFormatException ignored) {}
		}
		return defaultValue;
	}

	/**
	 * Main entry point for running the Panako HTTP API server standalone.
	 *
	 * <p>Usage: java -cp panako.jar be.panako.http.PanakoHttpServer [KEY=VALUE ...]</p>
	 *
	 * <p>Supported config (via args, system properties, or env vars):</p>
	 * <ul>
	 *   <li>SERVER_PORT (default: 8080)</li>
	 *   <li>SERVER_THREAD_POOL_SIZE (default: 10)</li>
	 *   <li>SERVER_MAX_UPLOAD_SIZE_MB (default: 100)</li>
	 *   <li>STRATEGY (default: OLAF) — passed to Panako's Config</li>
	 * </ul>
	 */
	public static void main(String[] args) {
		Locale.setDefault(Locale.US);

		// Initialize Panako config FIRST (loads defaults + config.properties)
		Config.getInstance();

		// Read config from environment variables (for Docker) — overrides config.properties
		for (Key key : Key.values()) {
			String envVal = System.getenv(key.name());
			if (envVal != null && !envVal.isEmpty()) {
				Config.set(key, envVal);
			}
		}

		// Parse KEY=VALUE arguments — overrides everything
		for (String arg : args) {
			if (arg.contains("=")) {
				String[] parts = arg.split("=", 2);
				try {
					Key key = Key.valueOf(parts[0]);
					Config.set(key, parts[1]);
				} catch (IllegalArgumentException e) {
					// Not a Panako Key — store as system property for our own config
					System.setProperty(parts[0], parts[1]);
				}
			}
		}
		String pipeEnvironment = Config.get(Key.DECODER_PIPE_ENVIRONMENT);
		String pipeArgument = Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG);
		String pipeCommand = Config.get(Key.DECODER_PIPE_COMMAND);
		String pipeLogFile = Config.get(Key.DECODER_PIPE_LOG_FILE);
		int pipeBuffer = Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE);
		PipeDecoder decoder = new PipeDecoder(pipeEnvironment, pipeArgument, pipeCommand, pipeLogFile, pipeBuffer);
		PipedAudioStream.setDecoder(decoder);

		// Read server config
		int port = getIntConfig("SERVER_PORT", DEFAULT_PORT);
		int threadPoolSize = getIntConfig("SERVER_THREAD_POOL_SIZE", DEFAULT_THREAD_POOL_SIZE);
		int maxUploadSizeMB = getIntConfig("SERVER_MAX_UPLOAD_SIZE_MB", DEFAULT_MAX_UPLOAD_SIZE_MB);

		// LMDB maxReaders is set from AVAILABLE_PROCESSORS — must be >= thread pool size
		int configuredProcessors = Config.getInt(Key.AVAILABLE_PROCESSORS);
		if (configuredProcessors < threadPoolSize) {
			Config.set(Key.AVAILABLE_PROCESSORS, String.valueOf(threadPoolSize));
		}

		try {
			PanakoHttpServer server = new PanakoHttpServer(port, threadPoolSize, maxUploadSizeMB);

			// Start Kafka workers if enabled.
			// PANAKO_WORKER_MODE:
			//   stage1 (default) — N workers on stage1 topics
			//   refine           — N workers on refine topics
			//   both             — N stage1 workers + N refine workers in the same JVM
			//                      (each pool uses its own consumer group → no contention)
			if (Config.getBoolean(Key.KAFKA_ENABLED)) {
				int workerCount = Config.getInt(Key.KAFKA_WORKER_THREADS);
				String workerMode = PanakoKafkaWorker.resolveWorkerMode();
				String[] pools = workerMode.equals(PanakoKafkaWorker.MODE_BOTH)
						? new String[] { PanakoKafkaWorker.MODE_STAGE1, PanakoKafkaWorker.MODE_REFINE }
						: new String[] { workerMode };
				for (String pool : pools) {
					for (int i = 0; i < workerCount; i++) {
						PanakoKafkaWorker kafkaWorker = new PanakoKafkaWorker(
								Strategy.getInstance(), server.writeLock, maxUploadSizeMB, pool);
						Thread kafkaThread = new Thread(kafkaWorker, "panako-kafka-worker-" + pool + "-" + i);
						kafkaThread.setDaemon(true);
						kafkaThread.start();
					}
				}
				System.out.printf("  Kafka: worker_mode=%s, %d worker(s) per pool, pools=%s (bootstrap: %s)%n",
						workerMode, workerCount, java.util.Arrays.toString(pools),
						Config.get(Key.KAFKA_BOOTSTRAP_SERVERS));
			}

			// Start Redis Stream workers if enabled (env REDIS_ENABLED=true).
			// Runs in parallel to Kafka workers — each backend is an independent queue.
			// PANAKO_WORKER_MODE controls which streams the workers subscribe to (same semantics as Kafka).
			String redisEnabled = System.getProperty("REDIS_ENABLED");
			if (redisEnabled == null) redisEnabled = System.getenv("REDIS_ENABLED");
			if (redisEnabled != null && redisEnabled.equalsIgnoreCase("true")) {
				int redisWorkerCount = getIntConfig("REDIS_WORKER_THREADS", 1);
				String workerMode = PanakoKafkaWorker.resolveWorkerMode();
				String[] pools = workerMode.equals(PanakoKafkaWorker.MODE_BOTH)
						? new String[] { PanakoRedisStreamWorker.MODE_STAGE1, PanakoRedisStreamWorker.MODE_REFINE }
						: new String[] { workerMode };
				for (String pool : pools) {
					for (int i = 0; i < redisWorkerCount; i++) {
						PanakoRedisStreamWorker redisWorker = new PanakoRedisStreamWorker(
								Strategy.getInstance(), server.writeLock, maxUploadSizeMB, pool);
						Thread redisThread = new Thread(redisWorker, "panako-redis-worker-" + pool + "-" + i);
						redisThread.setDaemon(true);
						redisThread.start();
					}
				}
				String redisHost = System.getenv("REDIS_HOST");
				if (redisHost == null) redisHost = "127.0.0.1";
				System.out.printf("  Redis: worker_mode=%s, %d worker(s) per pool, pools=%s (host: %s)%n",
						workerMode, redisWorkerCount, java.util.Arrays.toString(pools), redisHost);
			}

			server.start();
		} catch (IOException e) {
			LOG.severe("Failed to start HTTP server: " + e.getMessage());
			System.err.println("Failed to start HTTP server: " + e.getMessage());
			System.exit(1);
		}
	}
}
