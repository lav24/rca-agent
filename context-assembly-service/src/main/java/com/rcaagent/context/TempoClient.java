package com.rcaagent.context;

import com.rcaagent.context.model.ContextBundle.TraceSummary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class TempoClient {

    private final RestClient restClient;
    private final String baseUrl;

    public TempoClient(RcaProperties properties) {
        this.baseUrl = properties.tempoUrl();
        this.restClient = RestClient.create();
    }

    @SuppressWarnings("unchecked")
    public List<TraceSummary> fetchErrorTraces(String service, Instant start, Instant end) {
        String traceQl = String.format("{resource.service.name=\"%s\" && status=error}", service);
        String encodedQuery = URLEncoder.encode(traceQl, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/api/search?q=" + encodedQuery
                + "&start=" + start.getEpochSecond()
                + "&end=" + end.getEpochSecond()
                + "&limit=10");

        Map<String, Object> response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("traces") == null) {
            return List.of();
        }

        List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");
        return traces.stream()
                .map(t -> new TraceSummary(
                        String.valueOf(t.get("traceID")),
                        String.valueOf(t.getOrDefault("rootServiceName", "")),
                        parseLong(t.get("durationMs")),
                        true // this endpoint only searches status=error, so every hit qualifies
                ))
                .toList();
    }

    private long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
