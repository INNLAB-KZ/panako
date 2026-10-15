package be.panako.http;

import be.panako.util.Config;
import be.panako.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AudioDownloadRecoveryTest {
    @TempDir Path directory;

    @AfterEach void resetConfiguration() {
        Config.set(Key.AUDIO_CDN_HOST, "");
        Config.set(Key.AUDIO_ORIGIN_BASE_URL, "");
        Config.set(Key.AUDIO_ORIGIN_FALLBACK_TO_CDN, "TRUE");
    }

    @Test void retryReplacesPartialBytesWithCompleteBody() throws Exception {
        Path target = directory.resolve("audio");
        try (ScriptedServer server = new ScriptedServer(truncated(), complete("ok"))) {
            HttpUtil.downloadWithRetry(server.url(), target, 1024);
            assertEquals("ok", Files.readString(target));
            server.await();
        }
    }

    @Test void exhaustedTruncatedOriginFallsBackToCompleteCdnBody() throws Exception {
        Path target = directory.resolve("audio");
        try (ScriptedServer origin = new ScriptedServer(truncated(), truncated(), truncated());
             ScriptedServer cdn = new ScriptedServer(complete("complete-audio"))) {
            configure(origin);
            AudioDownloadRouter.download(cdn.url(), target, 1024);
            assertEquals("complete-audio", Files.readString(target));
            origin.await();
            cdn.await();
        }
    }

    @Test void bothTruncatedSourcesFailAndRemovePartialAudio() throws Exception {
        Path target = directory.resolve("audio");
        try (ScriptedServer origin = new ScriptedServer(truncated(), truncated(), truncated());
             ScriptedServer cdn = new ScriptedServer(truncated(), truncated(), truncated())) {
            configure(origin);
            IOException failure = assertThrows(IOException.class,
                    () -> AudioDownloadRouter.download(cdn.url(), target, 1024));
            assertTrue(failure.getMessage().contains("Incomplete download"));
            assertEquals(1, failure.getSuppressed().length);
            assertFalse(Files.exists(target));
            origin.await();
            cdn.await();
        }
    }

    private static void configure(ScriptedServer origin) {
        Config.set(Key.AUDIO_CDN_HOST, "127.0.0.1");
        Config.set(Key.AUDIO_ORIGIN_BASE_URL, origin.baseUrl());
        Config.set(Key.AUDIO_ORIGIN_FALLBACK_TO_CDN, "TRUE");
    }

    private static String truncated() {
        return "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\npartial-body";
    }

    private static String complete(String body) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body;
    }

    private static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> task;

        ScriptedServer(String... responses) throws IOException {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(20000);
            task = executor.submit(() -> {
                for (String response : responses) {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    } catch (IOException error) {
                        throw new RuntimeException(error);
                    }
                }
            });
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }
        String url() { return baseUrl() + "/audio"; }
        void await() throws Exception { task.get(5, TimeUnit.SECONDS); }

        @Override public void close() throws IOException {
            server.close();
            executor.shutdownNow();
        }
    }
}
