package be.panako.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioDownloadLengthTest {
    @TempDir Path directory;

    private void download(String response, Path target) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> sender = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            try {
                Method method = HttpUtil.class.getDeclaredMethod("downloadOnce", String.class, Path.class, long.class);
                method.setAccessible(true);
                try {
                    method.invoke(null, "http://127.0.0.1:" + server.getLocalPort() + "/audio", target, 1024L);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                    throw e;
                }
            } finally {
                try { sender.get(5, TimeUnit.SECONDS); }
                finally { executor.shutdownNow(); }
            }
        }
    }

    @Test void truncatedBodyFailsInsteadOfBecomingAudio() {
        assertThrows(IOException.class, () -> download(
                "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nabc", directory.resolve("partial")));
    }

    @Test void emptyBodyFails() {
        assertThrows(IOException.class, () -> download(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", directory.resolve("empty")));
    }

    @Test void completeBodyIsPreserved() throws Exception {
        Path target = directory.resolve("complete");
        download("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc", target);
        assertEquals("abc", Files.readString(target));
    }
}
