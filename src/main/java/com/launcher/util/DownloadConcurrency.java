package com.launcher.util;

import com.launcher.manager.SettingsManager;
import com.launcher.model.LauncherSettings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single source of truth for "how many downloads should run at once" across every bulk
 * download path that supports parallelism (library downloads, asset downloads, and modpack
 * file downloads). Backed by the "Download Threads" setting in Settings > Performance:
 * <ul>
 *   <li>Auto (default): scales with the machine's CPU core count, capped to a sane range so a
 *       36-core workstation doesn't open 72 sockets at once and get throttled/rate-limited by
 *       CDNs, while a 2-core machine still gets a little parallelism.</li>
 *   <li>Fixed: uses exactly {@code settings.downloadThreads}, clamped to 1-32.</li>
 * </ul>
 * Downloading is I/O-bound (waiting on the network), not CPU-bound, so using more threads than
 * CPU cores is normal and fine here - unlike a CPU-bound thread pool.
 */
public final class DownloadConcurrency {

    private DownloadConcurrency() {}

    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 32;

    /** Resolves the current thread count from settings (auto or fixed). Safe to call often -
     *  reads the already-loaded in-memory settings object, no disk I/O. */
    public static int resolveThreadCount() {
        LauncherSettings s;
        try {
            s = SettingsManager.getInstance().getSettings();
        } catch (Exception e) {
            return autoThreadCount(); // settings not available yet (e.g. very early startup) - fall back
        }
        if (s == null || s.downloadThreadsAuto) {
            return autoThreadCount();
        }
        return clamp(s.downloadThreads);
    }

    /** CPU-core-scaled default: 2 threads per core, clamped to [4, 16]. Network downloads are
     *  I/O-bound so this isn't "one thread per core" like a compute pool would be, but it's
     *  still sensible to give slower/older machines fewer concurrent connections by default. */
    private static int autoThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return clamp(cores * 2, 4, 16);
    }

    public static int clamp(int value) {
        return clamp(value, MIN_THREADS, MAX_THREADS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Convenience factory for a fixed-size, daemon-thread download pool sized from settings. */
    public static ExecutorService newDownloadPool(String namePrefix) {
        return Executors.newFixedThreadPool(resolveThreadCount(), daemonThreadFactory(namePrefix));
    }

    /** Same as {@link #newDownloadPool(String)} but capped to no more than {@code maxUseful}
     *  threads - handy when the number of items to download is small, so a batch of 3 files
     *  doesn't bother spinning up 16 threads for it. */
    public static ExecutorService newDownloadPool(String namePrefix, int maxUseful) {
        int threads = Math.max(1, Math.min(resolveThreadCount(), Math.max(1, maxUseful)));
        return Executors.newFixedThreadPool(threads, daemonThreadFactory(namePrefix));
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, namePrefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
