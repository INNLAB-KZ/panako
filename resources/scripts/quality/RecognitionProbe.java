package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.olaf.OlafStrategy;
import be.panako.util.Config;
import be.panako.util.Key;
import be.tarsos.dsp.io.PipeDecoder;
import be.tarsos.dsp.io.PipedAudioStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Offline monitor entry point: no HTTP server, Redis consumer, or result publication. */
public final class RecognitionProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: RecognitionProbe AUDIO OUTPUT_PREFIX");
        Locale.setDefault(Locale.US);
        Config.getInstance();
        for (Key key : Key.values()) {
            String value = System.getenv(key.name());
            if (value != null && !value.isEmpty()) Config.set(key, value);
        }
        PipedAudioStream.setDecoder(new PipeDecoder(
                Config.get(Key.DECODER_PIPE_ENVIRONMENT), Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG),
                Config.get(Key.DECODER_PIPE_COMMAND), Config.get(Key.DECODER_PIPE_LOG_FILE),
                Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE)));
        var peak = new AtomicLong();
        var sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max),
                0, 100, TimeUnit.MILLISECONDS);
        long started = System.nanoTime();
        try {
            var strategy = new OlafStrategy();
            List<QueryResult> results = MonitorHandler.monitorWithAbsoluteTimes(strategy, args[0]);
            // Scheduling order and temporary query paths are not recognition outputs.
            // Preserve duplicate detections; sort only to make comparison deterministic.
            List<String> raw = results.stream().map(RecognitionProbe::signature)
                    .sorted().collect(Collectors.toList());
            Files.write(Path.of(args[1] + ".raw.tsv"), raw);
            results.sort(Comparator.comparing(RecognitionProbe::signature));
            Files.writeString(Path.of(args[1] + ".json"),
                    MonitorHandler.buildResponseJson(strategy, results, args[0], 0));
            double elapsed = (System.nanoTime() - started) / 1e9;
            Files.writeString(Path.of(args[1] + ".metrics.json"), String.format(
                    "{\"elapsed_seconds\":%.3f,\"sampled_peak_heap_bytes\":%d,\"raw_matches\":%d}%n",
                    elapsed, peak.get(), results.size()));
            System.out.println("PROBE_COMPLETE raw_matches=" + results.size());
        } finally {
            sampler.shutdownNow();
        }
    }

    private static String signature(QueryResult r) {
        return String.join("\t", r.refIdentifier, Double.toString(r.queryStart),
                Double.toString(r.queryStop), Double.toString(r.refStart), Double.toString(r.refStop),
                Double.toString(r.score), Double.toString(r.timeFactor),
                Double.toString(r.frequencyFactor), Double.toString(r.percentOfSecondsWithMatches));
    }
}
