package com.rcaagent.context;

import com.rcaagent.context.model.ContextBundle;
import com.rcaagent.context.model.RcaDiagnosis;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RcaReasoningController {

    private final RcaReasoningService reasoningService;

    public RcaReasoningController(RcaReasoningService reasoningService) {
        this.reasoningService = reasoningService;
    }

    @PostMapping("/reason")
    public RcaDiagnosis reason(@RequestBody ContextBundle bundle) {
        return reasoningService.diagnose(bundle);
    }
}
