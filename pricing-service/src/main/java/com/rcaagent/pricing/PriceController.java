package com.rcaagent.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class PriceController {

    private static final Logger log = LoggerFactory.getLogger(PriceController.class);

    @GetMapping("/price")
    public Map<String, Object> getPrice(@RequestParam String route) {
        double rawPrice = ThreadLocalRandom.current().nextDouble(5000, 25000);
        double price = Math.round(rawPrice * 100.0) / 100.0;

        log.info("Price computed route={} price={}", route, price);

        return Map.of(
                "route", route,
                "price", price,
                "currency", "INR"
        );
    }
}
