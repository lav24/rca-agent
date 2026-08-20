package com.rcaagent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcaagent.context.model.ContextBundle;
import com.rcaagent.context.model.RcaDiagnosis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RcaReasoningService {

    private static final Logger log = LoggerFactory.getLogger(RcaReasoningService.class);

    private static final String SYSTEM_PROMPT = """
            You are an SRE assistant performing root cause analysis on a production incident.
            You will be given an alert, a summary of relevant metrics, recent error traces, and
            recent log lines for the affected service. Base your analysis ONLY on the evidence
            provided -- do not invent details that aren't present in the data.

            Respond with ONLY a single JSON object, no markdown fences, no extra text, matching
            exactly this shape:
            {
              "rootCause": "<one or two sentence diagnosis>",
              "confidence": "<high|medium|low>",
              "evidence": ["<specific fact from the data that supports this>", "..."],
              "recommendedAction": "<a concrete next step>"
            }
            """;

    private final RcaReasoningClient reasoningClient;
    private final ObjectMapper objectMapper;

    public RcaReasoningService(RcaReasoningClient reasoningClient, ObjectMapper objectMapper) {
        this.reasoningClient = reasoningClient;
        this.objectMapper = objectMapper;
    }

    public RcaDiagnosis diagnose(ContextBundle bundle) {
        String userPrompt;
        try {
            userPrompt = "Incident data:\n" + objectMapper.writeValueAsString(bundle);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize context bundle", e);
        }

        String rawOutput = reasoningClient.complete(SYSTEM_PROMPT, userPrompt);
        return parseDiagnosis(rawOutput);
    }

    private RcaDiagnosis parseDiagnosis(String rawOutput) {
        String cleaned = stripMarkdownFences(rawOutput);
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            List<String> evidence = objectMapper.convertValue(
                    node.path("evidence"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new RcaDiagnosis(
                    node.path("rootCause").asText(null),
                    node.path("confidence").asText(null),
                    evidence,
                    node.path("recommendedAction").asText(null),
                    rawOutput
            );
        } catch (Exception e) {
            log.warn("Failed to parse model output as structured JSON, returning raw text only: {}", e.getMessage());
            return new RcaDiagnosis(null, null, List.of(), null, rawOutput);
        }
    }

    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }
}
