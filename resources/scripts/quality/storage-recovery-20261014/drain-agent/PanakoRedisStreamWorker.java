package be.panako.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Standalone disposable fixture, never packaged into the production application. */
public final class PanakoRedisStreamWorker implements Runnable {
    private final String workerMode = "stage1";
    private volatile boolean running = true;
    private int started;
    private int completed;
    private int heartbeats;
    private boolean interrupted;

    private void save() throws Exception {
        String json = "{\"started\":" + started + ",\"completed\":" + completed
                + ",\"heartbeats\":" + heartbeats + ",\"interrupted\":" + interrupted + "}";
        Path temp = Path.of("/tmp/storage-drain-fixture.tmp");
        Files.writeString(temp, json);
        Files.move(temp, Path.of("/tmp/storage-drain-fixture.json"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void run() {
        try {
            while (running) {
                started++;
                for (int i = 0; i < 80; i++) {
                    heartbeats++;
                    save();
                    Thread.sleep(100);
                }
                completed++;
                save();
            }
        } catch (InterruptedException error) {
            interrupted = true;
            try { save(); } catch (Exception ignored) { }
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(new PanakoRedisStreamWorker(), "panako-redis-worker-stage1-0");
        worker.setDaemon(true);
        worker.start();
        Thread.currentThread().join();
    }
}
