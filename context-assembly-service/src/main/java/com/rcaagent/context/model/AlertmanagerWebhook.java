package com.rcaagent.context.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AlertmanagerWebhook(
        String status,
        List<Alert> alerts
) {
    public record Alert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            Instant startsAt,
            Instant endsAt,
            String fingerprint
    ) {
        public String service() {
            return labels.get("service");
        }

        public String alertname() {
            return labels.get("alertname");
        }
    }
}
