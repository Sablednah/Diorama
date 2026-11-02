package com.sablednah.diorama.neoforge;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.sablednah.diorama.DioramaConfig;
import net.minecraft.server.MinecraftServer;

/**
 * The render workers: a fixed pool whose threads stand aside while the server is struggling.
 *
 * <p>The threads are created at {@link Thread#MIN_PRIORITY}, but HotSpot on Linux ignores Java
 * thread priorities unless started with {@code -XX:ThreadPriorityPolicy}, so on most servers they
 * compete with the server thread as equals. So before each task (a tile, a zoom tile, a chunk
 * read) a worker checks the server's average tick time — the game's own 100-tick average, a plain
 * read — and while it is above {@code render.pauseAboveTickMs} it waits, a little at a time. The
 * wait is capped per task: a server that lags for reasons of its own still gets its map, just at a
 * crawl.</p>
 */
final class RenderPool extends ThreadPoolExecutor {

    private static final long STEP_MS = 100;
    private static final long MAX_WAIT_MS = 2_000;

    private final MinecraftServer server;

    RenderPool(int threads, ThreadFactory factory, MinecraftServer server) {
        super(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
        this.server = server;
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);
        long limitMs = DioramaConfig.PAUSE_ABOVE_TICK_MS.get();
        if (limitMs <= 0) {
            return;
        }
        long limit = limitMs * 1_000_000L;
        long waited = 0;
        while (waited < MAX_WAIT_MS && server.getAverageTickTimeNanos() > limit) {
            try {
                Thread.sleep(STEP_MS);
            } catch (InterruptedException e) {
                // Stopping: let the task see the interrupt and end.
                t.interrupt();
                return;
            }
            waited += STEP_MS;
        }
    }
}
