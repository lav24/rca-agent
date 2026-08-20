package com.rcaagent.context.model;

import java.util.List;

public record RcaDiagnosis(
        String rootCause,
        String confidence,
        List<String> evidence,
        String recommendedAction,
        String rawModelOutput
) {}
