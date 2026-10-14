package be.panako.ops;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deployment-only helper for old consumers; does not replace any application class. */
public final class IdleDrainAgent {
    private static final List<Worker> workers = new ArrayList<>();

    private static final class Worker {
        final Runnable target;
        final Field running;
        final String name;
        Thread thread;
        Thread monitored;
        boolean resumeRequested;

        Worker(Runnable target, Field running, Thread thread) {
            this.target = target;
            this.running = running;
            this.thread = thread;
            this.name = thread.getName();
        }
    }

    public static synchronized void agentmain(String command, Instrumentation instrumentation) throws Exception {
        if (!Set.of("drain", "status", "resume").contains(command)) {
            throw new IllegalArgumentException("Expected drain, status or resume");
        }
        if (workers.isEmpty()) {
            if (!command.equals("drain")) throw new IllegalStateException("No drain registered");
            instrumentation.redefineModule(Thread.class.getModule(), Set.of(), Map.of(),
                    Map.of("java.lang", Set.of(IdleDrainAgent.class.getModule())), Set.of(), Map.of());
            Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            List<Worker> found = new ArrayList<>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (!thread.getName().startsWith("panako-redis-worker-stage1-")) continue;
                Object target = targetField.get(thread);
                if (target == null || !target.getClass().getName().equals("be.panako.http.PanakoRedisStreamWorker")) {
                    throw new IllegalStateException("Unexpected consumer target");
                }
                Field mode = target.getClass().getDeclaredField("workerMode");
                Field running = target.getClass().getDeclaredField("running");
                mode.setAccessible(true);
                running.setAccessible(true);
                if (!"stage1".equals(mode.get(target)) || running.getType() != boolean.class
                        || !Modifier.isVolatile(running.getModifiers()) || !running.getBoolean(target)) {
                    throw new IllegalStateException("Unexpected consumer state");
                }
                found.add(new Worker((Runnable) target, running, thread));
            }
            if (found.size() != 1) throw new IllegalStateException("Expected exactly one stage1 consumer");
            workers.addAll(found);
        }
        for (Worker worker : workers) {
            if (command.equals("drain")) {
                worker.resumeRequested = false;
                // Do not call stop(), close the pool, interrupt a thread or touch Redis.
                // The existing loop completes processNext (including heartbeat/result/ACK)
                // before reading this volatile flag again.
                worker.running.setBoolean(worker.target, false);
            } else if (command.equals("resume")) {
                worker.resumeRequested = true;
                worker.running.setBoolean(worker.target, true);
                if (!worker.thread.isAlive()) {
                    start(worker);
                } else if (worker.monitored != worker.thread) {
                    Thread previous = worker.thread;
                    worker.monitored = previous;
                    Thread boundary = new Thread(() -> {
                        try {
                            previous.join();
                            synchronized (IdleDrainAgent.class) {
                                if (worker.resumeRequested && worker.thread == previous) start(worker);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }, "latency-drain-resume-boundary");
                    boundary.setDaemon(true);
                    boundary.start();
                }
            }
        }
        Worker worker = workers.get(0);
        String json = "{\"registered\":1,\"thread_alive\":" + worker.thread.isAlive()
                + ",\"running_flag\":" + worker.running.getBoolean(worker.target)
                + ",\"at_ms\":" + System.currentTimeMillis() + "}";
        Path output = Path.of("/tmp/storage-drain-status.json");
        Path temp = Path.of("/tmp/storage-drain-status.tmp");
        Files.writeString(temp, json);
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void start(Worker worker) {
        Thread replacement = new Thread(worker.target, worker.name);
        replacement.setDaemon(true);
        worker.thread = replacement;
        worker.monitored = null;
        replacement.start();
    }
}
