package com.proofflow.business;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public HealthResponse health() {
        return new HealthResponse("business-api", "ok");
    }

    public record HealthResponse(String service, String status) {}
}
