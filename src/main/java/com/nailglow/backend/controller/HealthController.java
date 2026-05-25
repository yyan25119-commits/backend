package com.nailglow.backend.controller;

import com.nailglow.backend.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/test")
    public Map<String, Object> test() {
        Integer styleCount = jdbc.queryForObject("select count(*) from nail_styles", Integer.class);
        return ApiResponse.ok(Map.of(
                "project", "NailGlow",
                "database", "connected",
                "styleCount", styleCount == null ? 0 : styleCount
        ));
    }
}
