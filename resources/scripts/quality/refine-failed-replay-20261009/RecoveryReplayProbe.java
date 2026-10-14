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

/** Prepare a normal refine response offline. No Redis connection or publication. */
public final class RecoveryReplayProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("AUDIO OUTPUT SEGMENTS RECORDING_ID");
        Config.getInstance();
        for (Key key : Key.values()) {
            String value = System.getenv(key.name());
            if (value != null && !value.isEmpty()) Config.set(key, value);
        }
        PipedAudioStream.setDecoder(new PipeDecoder(
                Config.get(Key.DECODER_PIPE_ENVIRONMENT), Config.get(Key.DECODER_PIPE_ENVIRONMENT_ARG),
                Config.get(Key.DECODER_PIPE_COMMAND), Config.get(Key.DECODER_PIPE_LOG_FILE),
                Config.getInt(Key.DECODER_PIPE_BUFFER_SIZE)));
        OlafStrategy strategy = new OlafStrategy();
        long started = System.currentTimeMillis();
        double duration = MonitorHandler.getAudioDuration(args[0]);
        List<QueryResult> results = new ArrayList<>();
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
        long processingMs = System.currentTimeMillis() - started;
        String monitorJson = MonitorHandler.buildResponseJson(strategy, results, args[0], processingMs);
        String response = "{\"recording_id\":\"" + HttpUtil.escapeJson(args[3]) + "\","
                + "\"duration_seconds\":" + String.format(java.util.Locale.ROOT, "%.1f", duration)
                + "," + monitorJson.substring(1);
        Files.writeString(Path.of(args[1]), response);
        System.out.println("RECOVERY_READY recording_id=" + args[3] + " processing_ms=" + processingMs
                + " raw_matches=" + results.size());
        System.exit(0);
    }
}
