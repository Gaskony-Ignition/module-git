package com.operametrix.ignition.git.automation;

import com.operametrix.ignition.git.records.GitAutomationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The git event bus: every mutating git operation fires here, and one worker thread delivers.
 *
 * <p>{@link #fire} never throws and never blocks. A git operation must not fail, or even slow
 * down, because someone's handler is broken or an endpoint is unreachable — so delivery is
 * asynchronous on a bounded queue, and a full queue drops and counts rather than applying
 * back-pressure to the commit.
 */
public final class GitEvents {

    private static final Logger logger = LoggerFactory.getLogger(GitEvents.class);

    private static final int QUEUE_CAPACITY = 256;
    private static final int RECENT_CAPACITY = 50;

    /** One delivered event plus what happened to it — the Automation tab's diagnostic. */
    public record LogEntry(GitEvent event, String delivery) {}

    private static final BlockingQueue<GitEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final Deque<LogEntry> recent = new ArrayDeque<>(RECENT_CAPACITY);

    private static final AtomicLong fired = new AtomicLong();
    private static final AtomicLong dropped = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();

    private static volatile Thread worker;
    private static volatile boolean running;

    private GitEvents() {
    }

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(GitEvents::run, "git-events");
        t.setDaemon(true);
        worker = t;
        t.start();
        logger.debug("Git event bus started.");
    }

    public static synchronized void shutdown() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        queue.clear();
    }

    /**
     * Hand an event to the bus. Safe to call from any thread, including one holding a JGit
     * repository — nothing here touches git.
     */
    public static void fire(GitEvent event) {
        if (event == null) {
            return;
        }
        fired.incrementAndGet();
        if (event.failed()) {
            failures.incrementAndGet();
        }
        if (!running) {
            // Still record it, so events raised during startup or after shutdown are visible in
            // the log rather than vanishing without trace.
            record(event, "not delivered (bus stopped)");
            return;
        }
        if (!queue.offer(event)) {
            long n = dropped.incrementAndGet();
            record(event, "dropped (queue full)");
            if (n == 1 || n % 100 == 0) {
                logger.warn("Git event queue full; {} event(s) dropped. A delivery target is too slow.", n);
            }
        }
    }

    private static void run() {
        while (running) {
            GitEvent event;
            try {
                event = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (event == null) {
                continue;
            }
            try {
                deliver(event);
            } catch (Throwable t) {
                // A delivery target must never be able to kill the worker: one broken handler
                // would otherwise silently stop every event on the gateway.
                logger.error("Git event delivery failed for a {} event.", event.type(), t);
                record(event, "delivery error: " + t);
            }
        }
    }

    private static void deliver(GitEvent event) {
        List<String> notes = new ArrayList<>();

        GitAutomationRecord cfg = GitAutomationRecord.get();
        if (cfg.isEnabled() && cfg.wants(event.type())) {
            notes.addAll(ScriptDelivery.deliver(event, cfg));
        } else if (!cfg.isEnabled()) {
            notes.add("scripts off");
        } else {
            notes.add("type not selected");
        }

        notes.addAll(TriggerDelivery.deliver(event));

        record(event, String.join("; ", notes));
    }

    private static void record(GitEvent event, String delivery) {
        synchronized (recent) {
            if (recent.size() >= RECENT_CAPACITY) {
                recent.removeLast();
            }
            recent.addFirst(new LogEntry(event, delivery));
        }
    }

    /** Most recent first. */
    public static List<LogEntry> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public static void clearLog() {
        synchronized (recent) {
            recent.clear();
        }
    }

    /**
     * The innermost cause's message — JGit and the RPC layer both wrap, and a wrapper's own
     * {@code toString()} names the exception class rather than what went wrong.
     */
    public static String reason(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg.trim();
    }

    public record Stats(long fired, long dropped, long failures, int queued, boolean running) {}

    public static Stats stats() {
        return new Stats(fired.get(), dropped.get(), failures.get(), queue.size(), running);
    }
}
