package com.launcher.util;

import com.launcher.manager.LauncherPaths;

import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards against accidentally running two copies of the launcher at once by holding an
 * exclusive {@link FileLock} on a small lock file in the launcher's data directory for as
 * long as this JVM is alive. The OS automatically releases the lock if the process dies or
 * exits (including a crash), so there's nothing to clean up manually.
 * <p>
 * Also runs a tiny local loopback socket server so a second launch attempt can ask the
 * already-running instance to bring its window to the front instead of just being told
 * "already running".
 */
public final class SingleInstanceGuard {

    private static RandomAccessFile lockRaf;
    private static FileChannel lockChannel;
    private static FileLock lock;
    private static ServerSocket focusServerSocket;

    private SingleInstanceGuard() {}

    /**
     * Attempts to acquire the single-instance lock.
     *
     * @return true if the lock was acquired (no other instance is running), false if another
     *         instance already holds it.
     */
    public static synchronized boolean tryAcquire() {
        try {
            Path lockPath = LauncherPaths.instanceLockFile();
            lockRaf = new RandomAccessFile(lockPath.toFile(), "rw");
            lockChannel = lockRaf.getChannel();
            lock = lockChannel.tryLock();
            return lock != null;
        } catch (OverlappingFileLockException ex) {
            return false;
        } catch (Exception ex) {
            // If we can't even attempt the lock (e.g. permissions issue), don't block the
            // launcher from starting — fail open rather than trapping the user out.
            return true;
        }
    }

    /** Releases the lock (and closes the underlying file) so a future launch can reacquire it. */
    public static synchronized void release() {
        try {
            if (lock != null) lock.release();
        } catch (Exception ignored) {}
        try {
            if (lockChannel != null) lockChannel.close();
        } catch (Exception ignored) {}
        try {
            if (lockRaf != null) lockRaf.close();
        } catch (Exception ignored) {}
        lock = null;
        lockChannel = null;
        lockRaf = null;
    }

    /**
     * Starts a background loopback socket server (only meant to be called by the instance that
     * successfully holds the lock) and writes its port to a file next to the lock file. Any
     * incoming connection is treated as a "bring yourself to the front" request and triggers
     * {@code onFocusRequested}.
     */
    public static synchronized void startFocusServer(Runnable onFocusRequested) {
        try {
            focusServerSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            int port = focusServerSocket.getLocalPort();
            Files.writeString(LauncherPaths.instancePortFile(), String.valueOf(port));

            Thread t = new Thread(() -> {
                while (!focusServerSocket.isClosed()) {
                    try (Socket ignored = focusServerSocket.accept()) {
                        onFocusRequested.run();
                    } catch (Exception ex) {
                        if (focusServerSocket.isClosed()) break;
                    }
                }
            }, "single-instance-focus-server");
            t.setDaemon(true);
            t.start();
        } catch (Exception ignored) {
            // If the focus server can't start, the "already running" dialog still works —
            // it'll just fall back to reporting that the running instance couldn't be reached.
        }
    }

    /**
     * Called by a second launch attempt to ask the already-running instance to bring its
     * window to the front.
     *
     * @return true if the request was successfully delivered, false if no running instance
     *         could be reached (e.g. it closed in the meantime).
     */
    public static boolean requestFocusOnRunningInstance() {
        try {
            Path portFile = LauncherPaths.instancePortFile();
            if (!Files.exists(portFile)) return false;
            int port = Integer.parseInt(Files.readString(portFile).trim());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1500);
                return true;
            }
        } catch (Exception ex) {
            return false;
        }
    }
}

