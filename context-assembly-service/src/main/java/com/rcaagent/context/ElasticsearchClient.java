package com.rcaagent.context;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchClient {

    private final RestClient restClient;

    public ElasticsearchClient(RcaProperties properties) {
        this.restClient = RestClient.create(properties.elasticsearchUrl());
    }

    @SuppressWarnings("unchecked")
    public List<String> fetchLogs(String service, Instant start, Instant end) {
        Map<String, Object> body = Map.of(
                "size", 20,
                "sort", List.of(Map.of("@timestamp", "desc")),
                "query", Map.of("bool", Map.of(
                        "filter", List.of(
                                Map.of("term", Map.of("container.labels.com_docker_compose_service", service)),
                                Map.of("range", Map.of("@timestamp", Map.of(
                                        "gte", start.toString(),
                                        "lte", end.toString()
                                )))
                        )
                ))
        );

        Map<String, Object> response = restClient.post()
                .uri("/.ds-filebeat-*/_search")
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            return List.of();
        }
        Map<String, Object> hitsWrapper = (Map<String, Object>) response.get("hits");
        if (hitsWrapper == null) {
            return List.of();
        }
        List<Map<String, Object>> hits = (List<Map<String, Object>>) hitsWrapper.get("hits");
        if (hits == null) {
            return List.of();
        }

        return hits.stream()
                .map(hit -> (Map<String, Object>) hit.get("_source"))
                .map(source -> String.valueOf(source.get("message")))
                .toList();
    }
}
