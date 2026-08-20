package com.rcaagent.context;

import com.rcaagent.context.model.AlertmanagerWebhook;
import com.rcaagent.context.model.ContextBundle;
import com.rcaagent.context.model.IncidentReport;
import com.rcaagent.context.model.RcaDiagnosis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);

    private final ContextAssemblyService contextAssemblyService;
    private final RcaReasoningService reasoningService;

    public AlertWebhookController(ContextAssemblyService contextAssemblyService, RcaReasoningService reasoningService) {
        this.contextAssemblyService = contextAssemblyService;
        this.reasoningService = reasoningService;
    }

    @PostMapping("/webhook/alert")
    public List<IncidentReport> handleAlert(@RequestBody AlertmanagerWebhook webhook) {
        return webhook.alerts().stream()
                .filter(alert -> "firing".equals(alert.status()))
                .map(alert -> {
                    log.info("Assembling context for alert={} service={}", alert.alertname(), alert.service());
                    ContextBundle bundle = contextAssemblyService.assemble(alert);
                    RcaDiagnosis diagnosis = reasoningService.diagnose(bundle);
                    log.warn("RCA diagnosis produced: rootCause={} confidence={}", diagnosis.rootCause(), diagnosis.confidence());
                    return new IncidentReport(bundle, diagnosis);
                })
                .toList();
    }
}
