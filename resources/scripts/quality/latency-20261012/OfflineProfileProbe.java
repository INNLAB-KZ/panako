package be.panako.http;

import be.panako.strategy.QueryResult;
import be.panako.strategy.olaf.OlafStrategy;
import be.panako.util.Config;
import be.panako.util.Key;
import be.tarsos.dsp.io.PipeDecoder;
import be.tarsos.dsp.io.PipedAudioStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Run the deployed monitor pipeline offline; never connects to Redis or publishes. */
public final class OfflineProfileProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("AUDIO OUTPUT SEGMENTS_OR_ALL RECORDING_ID");
        Locale.setDefault(Locale.US);
        Config.getInstance();
        for (Key key : Key.values()) {
            String value = System.getenv(key.name());
            if (value != null && !value.isEmpty()) Config.set(key, value);
        }
        PipedAudioStream.setDecoder(new PipeDecoder(Config.get(Key.DECODER_PIPE_ENVIRONMENT),
                Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG), Config.get(Key.DECODER_PIPE_COMMAND),
                Config.get(Key.DECODER_PIPE_LOG_FILE), Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE)));
        OlafStrategy strategy = new OlafStrategy();
        long started = System.nanoTime();
        double duration = MonitorHandler.getAudioDuration(args[0]);
        List<QueryResult> results = new ArrayList<>();
        if (args[2].equals("ALL")) {
            results.addAll(MonitorHandler.monitorWithAbsoluteTimes(strategy, args[0]));
        } else {
            for (String segment : args[2].split(",")) {
                String[] bounds = segment.split(":");
                int start = Integer.parseInt(bounds[0]);
                int length = Integer.parseInt(bounds[1]) - start;
                if (length <= 0) continue;
                Path chunk = MonitorHandler.extractAudioChunkDouble(args[0], start, length);
                try {
                    for (QueryResult r : MonitorHandler.monitorWithAbsoluteTimes(strategy, chunk.toString())) {
                        results.add(new QueryResult(r.queryPath, r.queryStart + start, r.queryStop + start,
                                r.refPath, r.refIdentifier, r.refStart, r.refStop, r.score,
                                r.timeFactor, r.frequencyFactor, r.percentOfSecondsWithMatches));
                    }
                } finally {
                    Files.deleteIfExists(chunk);
                }
            }
        }
        long processingMs = (System.nanoTime() - started) / 1_000_000;
        String body = MonitorHandler.buildResponseJson(strategy, results, args[0], processingMs);
        Files.writeString(Path.of(args[1]), "{\"recording_id\":\"" + HttpUtil.escapeJson(args[3])
                + "\",\"duration_seconds\":" + String.format("%.1f", duration) + "," + body.substring(1));
        System.out.println("PROFILE_COMPLETE processing_ms=" + processingMs + " raw_matches=" + results.size());
    }
}
