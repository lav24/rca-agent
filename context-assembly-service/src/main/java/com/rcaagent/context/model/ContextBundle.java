package com.rcaagent.context.model;

import java.time.Instant;
import java.util.List;

public record ContextBundle(
        AlertSummary alert,
        MetricsSummary metrics,
        List<TraceSummary> traces,
        List<String> logs
) {
    public record AlertSummary(
            String alertname,
            String service,
            String severity,
            String summary,
            Instant startedAt
    ) {}

    public record MetricsSummary(
            Double errorRate,
            Double p95LatencySeconds,
            String activeInjectedFault
    ) {}

    public record TraceSummary(
            String traceId,
            String rootServiceName,
            long durationMs,
            boolean hasError
    ) {}
}
