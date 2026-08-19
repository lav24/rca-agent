package com.rcaagent.pricing;

import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/fault")
public class FaultController {

    private final FaultState faultState;

    public FaultController(FaultState faultState) {
        this.faultState = faultState;
    }

    public record FaultRequest(String type, long durationSeconds) {}

    @PostMapping
    public Map<String, Object> setFault(@RequestBody FaultRequest request) {
        FaultState.FaultType type = FaultState.FaultType.valueOf(request.type().toUpperCase());
        if (type == FaultState.FaultType.NONE) {
            faultState.clear();
        } else {
            long duration = request.durationSeconds() > 0 ? request.durationSeconds() : 60;
            faultState.activate(type, duration);
        }
        return status();
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", faultState.current().name().toLowerCase());
        body.put("since", String.valueOf(faultState.since()));
        body.put("expiresAt", String.valueOf(faultState.expiresAt()));
        return body;
    }
}
