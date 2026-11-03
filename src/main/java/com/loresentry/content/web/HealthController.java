package com.loresentry.content.web;

import java.util.Map;

import com.loresentry.content.logging.AppLogger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        AppLogger.current().info("health requested");
        return Map.of("status", "ok");
    }

    @GetMapping("/")
    public Map<String, String> root() {
        AppLogger.current().info("root requested");
        return Map.of("service", "content-api");
    }
}
