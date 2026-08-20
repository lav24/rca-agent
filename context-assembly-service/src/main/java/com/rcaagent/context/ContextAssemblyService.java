package com.rcaagent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcaagent.context.model.AlertmanagerWebhook.Alert;
import com.rcaagent.context.model.ContextBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Service
public class ContextAssemblyService {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblyService.class);

    private final PrometheusClient prometheusClient;
    private final TempoClient tempoClient;
    private final ElasticsearchClient elasticsearchClient;
    private final RcaProperties properties;
    private final ObjectMapper objectMapper;

    public ContextAssemblyService(
            PrometheusClient prometheusClient,
            TempoClient tempoClient,
            ElasticsearchClient elasticsearchClient,
            RcaProperties properties,
            ObjectMapper objectMapper
    ) {
        this.prometheusClient = prometheusClient;
        this.tempoClient = tempoClient;
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ContextBundle assemble(Alert alert) {
        String service = alert.service();
        Instant now = Instant.now();
        Instant windowStart = alert.startsAt().minusSeconds(properties.lookbackSeconds());
        Instant windowEnd = now;

        ContextBundle.AlertSummary alertSummary = new ContextBundle.AlertSummary(
                alert.alertname(),
                service,
                alert.labels().get("severity"),
                alert.annotations().get("summary"),
                alert.startsAt()
        );

        ContextBundle.MetricsSummary metrics = safely(
                () -> prometheusClient.fetchMetrics(service),
                new ContextBundle.MetricsSummary(null, null, null),
                "prometheus");
        List<ContextBundle.TraceSummary> traces = safely(
                () -> tempoClient.fetchErrorTraces(service, windowStart, windowEnd),
                List.of(),
                "tempo");
        List<String> logs = safely(
                () -> elasticsearchClient.fetchLogs(service, windowStart, windowEnd),
                List.of(),
                "elasticsearch");

        ContextBundle bundle = new ContextBundle(alertSummary, metrics, traces, logs);

        logBundle(bundle);
        return bundle;
    }

    private <T> T safely(Supplier<T> query, T fallback, String source) {
        try {
            return query.get();
        } catch (Exception e) {
            log.warn("Failed to fetch data from {}: {}", source, e.getMessage());
            return fallback;
        }
    }

    private void logBundle(ContextBundle bundle) {
        try {
            log.warn("Context bundle assembled: {}", objectMapper.writeValueAsString(bundle));
        } catch (Exception e) {
            log.error("Failed to serialize context bundle for logging", e);
        }
    }
}
