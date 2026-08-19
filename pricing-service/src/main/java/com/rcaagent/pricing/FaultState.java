package com.rcaagent.pricing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class FaultState {

    public enum FaultType { NONE, LATENCY, ERROR, BAD_DATA }

    private final AtomicReference<FaultType> active = new AtomicReference<>(FaultType.NONE);
    private final AtomicReference<Instant> since = new AtomicReference<>();
    private final AtomicReference<Instant> expiresAt = new AtomicReference<>();

    private final AtomicInteger latencyGauge = new AtomicInteger(0);
    private final AtomicInteger errorGauge = new AtomicInteger(0);
    private final AtomicInteger badDataGauge = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingClear;

    public FaultState(MeterRegistry registry) {
        Gauge.builder("fault_injection_active", latencyGauge, AtomicInteger::get)
                .tag("type", "latency").register(registry);
        Gauge.builder("fault_injection_active", errorGauge, AtomicInteger::get)
                .tag("type", "error").register(registry);
        Gauge.builder("fault_injection_active", badDataGauge, AtomicInteger::get)
                .tag("type", "bad_data").register(registry);
    }

    public synchronized void activate(FaultType type, long durationSeconds) {
        if (pendingClear != null) {
            pendingClear.cancel(false);
        }
        active.set(type);
        since.set(Instant.now());
        expiresAt.set(Instant.now().plusSeconds(durationSeconds));
        setGauges(type);
        pendingClear = scheduler.schedule(this::clear, durationSeconds, TimeUnit.SECONDS);
    }

    public synchronized void clear() {
        active.set(FaultType.NONE);
        since.set(null);
        expiresAt.set(null);
        setGauges(FaultType.NONE);
    }

    private void setGauges(FaultType type) {
        latencyGauge.set(type == FaultType.LATENCY ? 1 : 0);
        errorGauge.set(type == FaultType.ERROR ? 1 : 0);
        badDataGauge.set(type == FaultType.BAD_DATA ? 1 : 0);
    }

    public FaultType current() {
        return active.get();
    }

    public Instant since() {
        return since.get();
    }

    public Instant expiresAt() {
        return expiresAt.get();
    }
}
