package com.rcaagent.context;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ContextAssemblyApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContextAssemblyApplication.class, args);
    }
}
