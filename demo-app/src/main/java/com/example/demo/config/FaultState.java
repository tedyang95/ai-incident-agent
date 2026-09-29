package com.example.demo.config;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 故障状态管理（Fault State Manager）
 * 控制哪些故障注入（fault injection）当前处于激活状态。
 * 同时负责执行内存泄漏（memory leak）——持续分配内存不释放。
 */
@Component
public class FaultState {

    private final AtomicBoolean errorEnabled = new AtomicBoolean(false);
    private final AtomicBoolean latencyEnabled = new AtomicBoolean(false);
    private final AtomicBoolean memoryLeakEnabled = new AtomicBoolean(false);

    // 内存泄漏用：持有引用不释放
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

    public List<String> getActiveFaults() {
        List<String> faults = new ArrayList<>();
        if (errorEnabled.get()) faults.add("error");
        if (latencyEnabled.get()) faults.add("latency");
        if (memoryLeakEnabled.get()) faults.add("memory_leak");
        return faults;
    }

    public void reset() {
        errorEnabled.set(false);
        latencyEnabled.set(false);
        memoryLeakEnabled.set(false);
        memoryLeakHolder.clear();
        System.gc();
    }

    /**
     * 启动内存泄漏线程：每秒分配 10MB 内存且不释放
     * 这会触发 JVM heap memory usage > 85% 的告警
     */
    private void startMemoryLeak() {
        Thread leakThread = new Thread(() -> {
            while (memoryLeakEnabled.get()) {
                try {
                    // 每次分配 10MB
                    memoryLeakHolder.add(new byte[10 * 1024 * 1024]);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (OutOfMemoryError e) {
                    // 内存溢出时停止，避免 JVM 崩溃
                    memoryLeakEnabled.set(false);
                    break;
                }
            }
        }, "memory-leak-thread");
        leakThread.setDaemon(true);
        leakThread.start();
    }
}
