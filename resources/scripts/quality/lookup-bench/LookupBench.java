package be.panako.http;

import be.panako.strategy.*;
import be.panako.util.*;
import be.tarsos.dsp.io.*;
import java.nio.file.*;
import java.util.*;

/** Isolated, read-only reproduction using the deployed recognition classes. */
public class LookupBench {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        Config.getInstance();
        for (Key key : Key.values()) {
            String value = System.getenv(key.name());
            if (value != null && !value.isEmpty()) Config.set(key, value);
        }
        PipedAudioStream.setDecoder(new PipeDecoder(Config.get(Key.DECODER_PIPE_ENVIRONMENT),
            Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG), Config.get(Key.DECODER_PIPE_COMMAND),
            Config.get(Key.DECODER_PIPE_LOG_FILE), Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE)));
        Strategy strategy = Strategy.getInstance();
        long start = System.nanoTime();
        double duration = MonitorHandler.getAudioDuration(args[0]);
        List<QueryResult> results = MonitorHandler.monitorWithAbsoluteTimes(strategy, args[0]);
        long elapsed = (System.nanoTime() - start) / 1000000;
        List<String> canonical = new ArrayList<>();
        for (QueryResult r : results) canonical.add(String.join("\t", r.refIdentifier,
            String.valueOf(r.refPath), Double.toHexString(r.queryStart), Double.toHexString(r.queryStop),
            Double.toHexString(r.refStart), Double.toHexString(r.refStop), Double.toHexString(r.score),
            Double.toHexString(r.timeFactor), Double.toHexString(r.frequencyFactor),
            Double.toHexString(r.percentOfSecondsWithMatches)));
        Collections.sort(canonical);
        Files.write(Path.of(args[1] + ".matches.txt"), canonical);
        Files.writeString(Path.of(args[1] + ".response.json"),
            MonitorHandler.buildResponseJson(strategy, results, args[0], elapsed));
        Files.writeString(Path.of(args[1] + ".timing.json"), String.format(Locale.ROOT,
            "{\"duration_seconds\":%.3f,\"processing_time_ms\":%d,\"raw_matches\":%d}", duration, elapsed, results.size()));
        System.out.println("BENCH_DONE elapsed_ms=" + elapsed + " raw_matches=" + results.size());
    }
}
