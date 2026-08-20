package com.rcaagent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rca")
public record RcaProperties(
        String prometheusUrl,
        String tempoUrl,
        String elasticsearchUrl,
        String litellmUrl,
        long lookbackSeconds
) {}
