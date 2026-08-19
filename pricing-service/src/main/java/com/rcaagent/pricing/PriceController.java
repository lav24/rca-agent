package com.rcaagent.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class PriceController {

    private static final Logger log = LoggerFactory.getLogger(PriceController.class);
    private static final long INJECTED_LATENCY_MS = 3000;

    private final FaultState faultState;

    public PriceController(FaultState faultState) {
        this.faultState = faultState;
    }

    @GetMapping("/price")
    public Map<String, Object> getPrice(@RequestParam String route) {
        FaultState.FaultType fault = faultState.current();

        if (fault == FaultState.FaultType.LATENCY) {
            log.warn("Injected fault active: type=latency route={} delayMs={}", route, INJECTED_LATENCY_MS);
            sleep(INJECTED_LATENCY_MS);
        }

        if (fault == FaultState.FaultType.ERROR) {
            log.error("Injected fault active: type=error route={}", route);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "pricing computation failed");
        }

        double rawPrice = ThreadLocalRandom.current().nextDouble(5000, 25000);
        double price = Math.round(rawPrice * 100.0) / 100.0;

        if (fault == FaultState.FaultType.BAD_DATA) {
            log.warn("Injected fault active: type=bad_data route={}", route);
            price = -1.0;
        }

        log.info("Price computed route={} price={}", route, price);

        return Map.of(
                "route", route,
                "price", price,
                "currency", "INR"
        );
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
