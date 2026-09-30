package com.example.demo.config;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fault state manager.
 * <p>
 * Tracks which fault-injection modes are currently active and owns the
 * memory-leak simulation (allocates memory without ever releasing it).
 */
@Component
public class FaultState {

    private final AtomicBoolean errorEnabled = new AtomicBoolean(false);
    private final AtomicBoolean latencyEnabled = new AtomicBoolean(false);
    private final AtomicBoolean memoryLeakEnabled = new AtomicBoolean(false);
    private final AtomicBoolean downstreamEnabled = new AtomicBoolean(false);

    // Retains references so allocated memory is never released (memory-leak simulation).
    private final List<byte[]> memoryLeakHolder = new ArrayList<>();

    public boolean isErrorEnabled() {
        return errorEnabled.get();
    }

    public void setErrorEnabled(boolean enabled) {
        this.errorEnabled.set(enabled);
    }

    public boolean isLatencyEnabled() {
        return latencyEnabled.get();
    }

    public void setLatencyEnabled(boolean enabled) {
        this.latencyEnabled.set(enabled);
    }

    public boolean isMemoryLeakEnabled() {
        return memoryLeakEnabled.get();
    }

    public void setMemoryLeakEnabled(boolean enabled) {
        this.memoryLeakEnabled.set(enabled);
        if (enabled) {
            startMemoryLeak();
        }
    }

    public boolean isDownstreamEnabled() {
        return downstreamEnabled.get();
    }

    public void setDownstreamEnabled(boolean enabled) {
        this.downstreamEnabled.set(enabled);
    }

    public List<String> getActiveFaults() {
        List<String> faults = new ArrayList<>();
        if (errorEnabled.get()) faults.add("error");
        if (latencyEnabled.get()) faults.add("latency");
        if (memoryLeakEnabled.get()) faults.add("memory_leak");
        if (downstreamEnabled.get()) faults.add("downstream");
        return faults;
    }

    public void reset() {
        errorEnabled.set(false);
        latencyEnabled.set(false);
        memoryLeakEnabled.set(false);
        downstreamEnabled.set(false);
        memoryLeakHolder.clear();
        System.gc();
    }

    /**
     * Starts a daemon thread that allocates ~10 MB of memory per second without
     * releasing it, pushing JVM heap usage above the 85% alert threshold.
     */
    private void startMemoryLeak() {
        Thread leakThread = new Thread(() -> {
            while (memoryLeakEnabled.get()) {
                try {
                    // Allocate another 10 MB block.
                    memoryLeakHolder.add(new byte[10 * 1024 * 1024]);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (OutOfMemoryError e) {
                    // Stop on OOM to keep the JVM alive.
                    memoryLeakEnabled.set(false);
                    break;
                }
            }
        }, "memory-leak-thread");
        leakThread.setDaemon(true);
        leakThread.start();
    }
}
