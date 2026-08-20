package com.rcaagent.context;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class RcaReasoningClient {

    private final RestClient restClient;

    public RcaReasoningClient(RcaProperties properties) {
        this.restClient = RestClient.create(properties.litellmUrl());
    }

    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", "rca-llm",
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object")
        );

        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(Map.class);

        return extractContent(response);
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return String.valueOf(message.get("content"));
    }
}
