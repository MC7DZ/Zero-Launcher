package com.launcher.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks in-progress (and recently finished) downloads across the whole app — mod installs,
 * mod updates, the Dawn client jar, instance/game file installs, etc. — so a single floating
 * UI button can show "something is downloading" without every call site needing to know about
 * every other one.
 *
 * This is intentionally a plain status tracker (name + free-text status + optional percent)
 * rather than a byte-accurate progress system, since most of the underlying download calls
 * (see HttpUtil) don't currently report byte-level progress either — the goal is a single
 * place to observe "what's downloading right now," not a precise progress bar.
 */
public final class DownloadManager {

    public enum State { RUNNING, PAUSED, DONE, FAILED, CANCELLED }

    public static final class DownloadItem {
        public final String id;
        public volatile String name;
        public volatile String status;
        /** -1 = indeterminate; otherwise 0-100. */
        public volatile int percent = -1;
        public volatile State state = State.RUNNING;
        public final long startedAt = System.currentTimeMillis();
        /** Set once the worker thread notices the cancel request and should stop working. */
        public volatile boolean cancelRequested = false;
        /** The worker thread doing the actual download, if it has registered itself via
         *  {@link #bindThread(String)}. Used so Cancel can actually interrupt in-flight work. */
        public volatile Thread thread;
        /** Remembers status text from just before pausing so it can be restored on resume. */
        private volatile String statusBeforePause;

        DownloadItem(String id, String name) {
            this.id = id;
            this.name = name;
            this.status = "Starting…";
        }
    }

    private static final DownloadManager INSTANCE = new DownloadManager();
    public static DownloadManager getInstance() { return INSTANCE; }

    // Newest-first isn't required for correctness, but the UI reads it that way for a natural
    // "most recent activity on top" feel — see snapshotNewestFirst().
    private final List<DownloadItem> items = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, DownloadItem> byId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    // Completed/failed items older than this are pruned on the next change, so the list
    // doesn't grow forever across a long launcher session.
    private static final long FINISHED_RETENTION_MS = 5 * 60 * 1000;
    private static final int MAX_ITEMS = 50;

    private DownloadManager() {}

    /** Registers a listener invoked (on whatever thread the change happened on) whenever a
     *  download starts, updates, or finishes. UI code should hop to the EDT itself. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public String start(String name) {
        String id = "dl-" + idSeq.incrementAndGet();
        DownloadItem item = new DownloadItem(id, name);
        items.add(0, item);
        byId.put(id, item);
        prune();
        fireChanged();
        return id;
    }

    public void update(String id, String status) {
        DownloadItem item = byId.get(id);
        if (item == null) return;
        item.status = status;
        fireChanged();
    }

    public void update(String id, String status, int percent) {
        DownloadItem item = byId.get(id);
        if (item == null) return;
        item.status = status;
        item.percent = percent;
        fireChanged();
    }

    public void finish(String id) {
        finish(id, "Complete");
    }

    public void finish(String id, String status) {
        DownloadItem item = byId.get(id);
        if (item == null || item.state == State.CANCELLED) return;
        item.state = State.DONE;
        item.status = status;
        item.percent = 100;
        fireChanged();
    }

    public void fail(String id, String status) {
        DownloadItem item = byId.get(id);
        if (item == null || item.state == State.CANCELLED) return;
        item.state = State.FAILED;
        item.status = status != null ? status : "Failed";
        fireChanged();
    }

    /** Registers the calling thread as the worker actually doing the download for {@code id},
     *  so {@link #requestCancel(String)} can interrupt real in-flight network/file work
     *  instead of only flipping a flag nobody checks. Call this as the first line inside the
     *  background thread's run(). */
    public void bindThread(String id) {
        DownloadItem item = byId.get(id);
        if (item != null) item.thread = Thread.currentThread();
    }

    /** Marks a running download as paused. Loops in worker code should call
     *  {@link #awaitIfPaused(String)} between chunks of work to actually honor this. */
    public void requestPause(String id) {
        DownloadItem item = byId.get(id);
        if (item == null || item.state != State.RUNNING) return;
        item.statusBeforePause = item.status;
        item.state = State.PAUSED;
        item.status = "Paused";
        fireChanged();
    }

    /** Resumes a paused download. */
    public void requestResume(String id) {
        DownloadItem item = byId.get(id);
        if (item == null || item.state != State.PAUSED) return;
        item.state = State.RUNNING;
        if (item.statusBeforePause != null) item.status = item.statusBeforePause;
        fireChanged();
    }

    /** Requests cancellation of a running or paused download. Sets the cancel flag (which
     *  worker code should poll via {@link #isCancelled(String)}) and, if a worker thread has
     *  registered itself via {@link #bindThread(String)}, interrupts it — which is enough to
     *  abort an in-progress HttpClient call or Thread.sleep. */
    public void requestCancel(String id) {
        DownloadItem item = byId.get(id);
        if (item == null || (item.state != State.RUNNING && item.state != State.PAUSED)) return;
        item.cancelRequested = true;
        item.state = State.CANCELLED;
        item.status = "Cancelled";
        Thread t = item.thread;
        if (t != null) t.interrupt();
        fireChanged();
    }

    /** True once cancellation has been requested for this download. Worker loops can poll
     *  this to bail out early between items/chunks. */
    public boolean isCancelled(String id) {
        DownloadItem item = byId.get(id);
        return item != null && item.cancelRequested;
    }

    /** Blocks the calling (worker) thread while the download is paused. Returns promptly if the
     *  download isn't paused, and stops waiting (returns) if it gets cancelled while paused. */
    public void awaitIfPaused(String id) {
        DownloadItem item = byId.get(id);
        if (item == null) return;
        while (item.state == State.PAUSED) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean hasActive() {
        for (DownloadItem item : items) {
            if (item.state == State.RUNNING || item.state == State.PAUSED) return true;
        }
        return false;
    }

    public int activeCount() {
        int count = 0;
        for (DownloadItem item : items) {
            if (item.state == State.RUNNING || item.state == State.PAUSED) count++;
        }
        return count;
    }

    /** Snapshot of all tracked downloads, most recently started first. */
    public List<DownloadItem> snapshotNewestFirst() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    private void prune() {
        long now = System.currentTimeMillis();
        for (DownloadItem item : new ArrayList<>(items)) {
            boolean finished = item.state != State.RUNNING && item.state != State.PAUSED;
            boolean stale = finished && (now - item.startedAt) > FINISHED_RETENTION_MS;
            boolean overflow = items.size() > MAX_ITEMS && finished;
            if (stale || overflow) {
                items.remove(item);
                byId.remove(item.id);
            }
        }
    }

    private void fireChanged() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (Exception ignored) {
                // A misbehaving listener shouldn't break download tracking for everyone else.
            }
        }
    }
}
