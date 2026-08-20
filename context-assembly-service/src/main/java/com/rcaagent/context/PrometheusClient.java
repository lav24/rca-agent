package com.rcaagent.context;

import com.rcaagent.context.model.ContextBundle.MetricsSummary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PrometheusClient {

    // Different services expose their HTTP metrics under different name
    // prefixes: Java/Micrometer auto-names it http.server.requests ->
    // http_server_requests_seconds_*, while placement-service's hand-written
    // Go middleware uses the idiomatic Prometheus name http_request_duration_seconds_*.
    private static final Map<String, String> METRIC_PREFIX_BY_SERVICE = Map.of(
            "pricing-service", "http_server_requests_seconds",
            "placement-service", "http_request_duration_seconds"
    );

    private final RestClient restClient;
    private final String baseUrl;

    public PrometheusClient(RcaProperties properties) {
        this.baseUrl = properties.prometheusUrl();
        this.restClient = RestClient.create();
    }

    public MetricsSummary fetchMetrics(String service) {
        String prefix = METRIC_PREFIX_BY_SERVICE.get(service);
        if (prefix == null) {
            return new MetricsSummary(null, null, null);
        }

        Double errorRate = instantQuery(String.format(
                "sum(rate(%s_count{job=\"%s\",status=~\"5..\"}[1m])) / sum(rate(%s_count{job=\"%s\"}[1m]))",
                prefix, service, prefix, service));

        Double p95Latency = instantQuery(String.format(
                "histogram_quantile(0.95, sum(rate(%s_bucket{job=\"%s\"}[1m])) by (le))",
                prefix, service));

        String activeFault = fetchActiveFault(service);

        return new MetricsSummary(errorRate, p95Latency, activeFault);
    }

    @SuppressWarnings("unchecked")
    private Double instantQuery(String promql) {
        Map<String, Object> response = restClient.get()
                .uri(queryUri(promql))
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> result = extractResultVector(response);
        if (result.isEmpty()) {
            return null;
        }
        List<Object> value = (List<Object>) result.get(0).get("value");
        String raw = String.valueOf(value.get(1));
        try {
            double parsed = Double.parseDouble(raw);
            return Double.isNaN(parsed) ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchActiveFault(String service) {
        String promql = String.format("fault_injection_active{job=\"%s\"} == 1", service);
        Map<String, Object> response = restClient.get()
                .uri(queryUri(promql))
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> result = extractResultVector(response);
        return result.stream()
                .map(series -> (Map<String, String>) series.get("metric"))
                .map(metric -> metric.get("type"))
                .collect(Collectors.joining(","));
    }

    private URI queryUri(String promql) {
        String encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8);
        return URI.create(baseUrl + "/api/v1/query?query=" + encoded);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractResultVector(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) {
            return List.of();
        }
        List<Map<String, Object>> result = (List<Map<String, Object>>) data.get("result");
        return result == null ? List.of() : result;
    }
}
