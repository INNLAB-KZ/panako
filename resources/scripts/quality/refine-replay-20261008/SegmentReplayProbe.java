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
public final class SegmentReplayProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: SegmentReplayProbe AUDIO OUTPUT_PREFIX START:END,...");
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
            long processingStarted = System.nanoTime();
            double duration = MonitorHandler.getAudioDuration(args[0]);
            List<QueryResult> results = new java.util.ArrayList<>();
            for (String segment : args[2].split(",")) {
                String[] bounds = segment.split(":");
                int start = Integer.parseInt(bounds[0]);
                int length = Integer.parseInt(bounds[1]) - start;
                if (length <= 0) continue;
                Path chunk = null;
                try {
                    chunk = MonitorHandler.extractAudioChunkDouble(args[0], start, length);
                    for (QueryResult r : MonitorHandler.monitorWithAbsoluteTimes(strategy, chunk.toString())) {
                        results.add(new QueryResult(r.queryPath, r.queryStart + start, r.queryStop + start,
                            r.refPath, r.refIdentifier, r.refStart, r.refStop, r.score, r.timeFactor,
                            r.frequencyFactor, r.percentOfSecondsWithMatches));
                    }
                } finally {
                    if (chunk != null) Files.deleteIfExists(chunk);
                }
            }
            long processingMs = (System.nanoTime() - processingStarted) / 1_000_000;
            // Scheduling order and temporary query paths are not recognition outputs.
            // Preserve duplicate detections; sort only to make comparison deterministic.
            List<String> raw = results.stream().map(SegmentReplayProbe::signature)
                    .sorted().collect(Collectors.toList());
            Files.write(Path.of(args[1] + ".raw.tsv"), raw);
            results.sort(Comparator.comparing(SegmentReplayProbe::signature));
            Files.writeString(Path.of(args[1] + ".json"),
                    MonitorHandler.buildResponseJson(strategy, results, args[0], processingMs));
            double elapsed = (System.nanoTime() - started) / 1e9;
            Files.writeString(Path.of(args[1] + ".metrics.json"), String.format(
                    "{\"elapsed_seconds\":%.3f,\"sampled_peak_heap_bytes\":%d,\"raw_matches\":%d}%n",
                    elapsed, peak.get(), results.size()));
            System.out.println("PROBE_COMPLETE raw_matches=" + results.size() + " processing_ms=" + processingMs + " audio_seconds=" + duration);
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
