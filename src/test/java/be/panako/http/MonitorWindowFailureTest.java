package be.panako.http;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitorWindowFailureTest {
    @Test
    void databaseFailureInvalidatesOtherwiseSuccessfulWindows() {
        var sqlFailure = new java.sql.SQLException("connection lost", "08006");
        var lookupFailure = new IllegalStateException("Failed to query OLAF fingerprints", sqlFailure);
        var failure = assertThrows(IOException.class, () -> MonitorHandler.awaitWindowTasks(
                List.of(CompletableFuture.completedFuture(null),
                        CompletableFuture.failedFuture(lookupFailure)), 1, TimeUnit.SECONDS));
        assertSame(lookupFailure, failure.getCause());
        assertSame(sqlFailure, failure.getCause().getCause());
    }

    @Test
    void failedWindowInvalidatesOtherwiseSuccessfulRecording() {
        var failed = CompletableFuture.failedFuture(new IOException("extract failed"));
        assertThrows(IOException.class, () -> MonitorHandler.awaitWindowTasks(
                List.of(CompletableFuture.completedFuture(null), failed), 1, TimeUnit.SECONDS));
    }

    @Test
    void fatalHeapErrorPropagatesToRedisWithoutSuccess() {
        var error = new OutOfMemoryError("simulated heap exhaustion");
        assertSame(error, assertThrows(OutOfMemoryError.class,
                () -> MonitorHandler.awaitWindowTasks(
                        List.of(CompletableFuture.failedFuture(error)), 1, TimeUnit.SECONDS)));
    }

    @Test
    void timeoutAndCancellationInvalidateRecording() {
        var pending = new CompletableFuture<Void>();
        assertThrows(IOException.class, () -> MonitorHandler.awaitWindowTasks(
                List.of(pending), 1, TimeUnit.MILLISECONDS));
        pending.cancel(true);
        assertThrows(IOException.class, () -> MonitorHandler.awaitWindowTasks(
                List.of(pending), 1, TimeUnit.SECONDS));
    }

    @Test
    void interruptionIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> MonitorHandler.awaitWindowTasks(
                    List.of(new CompletableFuture<>()), 1, TimeUnit.SECONDS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationWaitsForActualWindowExit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var cleanup = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var allowExit = new CountDownLatch(1);
        var window = executor.submit(() -> {
            started.countDown();
            while (allowExit.getCount() > 0) {
                try { allowExit.await(); }
                catch (InterruptedException ignored) { interrupted.countDown(); }
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var stopped = cleanup.submit(() -> MonitorHandler.stopWindowTasks(executor, List.of(window)));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(stopped.isDone(), "Cancelled future is not proof that its thread exited");
            allowExit.countDown();
            stopped.get(2, TimeUnit.SECONDS);
            assertTrue(executor.isTerminated());
        } finally {
            allowExit.countDown();
            executor.shutdownNow();
            cleanup.shutdownNow();
        }
    }
}
