package com.nailglow.backend.controller;

import com.nailglow.backend.ApiResponse;
import com.nailglow.backend.service.AuthService;
import com.nailglow.backend.service.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/user/login")
    public Map<String, Object> userLogin(@RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(authService.login(text(payload, "account"), text(payload, "password"), "user"));
    }

    @PostMapping("/admin/login")
    public Map<String, Object> adminLogin(@RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(authService.login(text(payload, "account"), text(payload, "password"), "admin"));
    }

    @PostMapping({"/register", "/user/register"})
    public Map<String, Object> register(@RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(authService.register(
                text(payload, "account"),
                text(payload, "password"),
                text(payload, "nickname")
        ));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return authService.authenticate(request)
                .map(user -> ApiResponse.ok(Map.of("user", userPayload(user))))
                .orElseGet(() -> ApiResponse.fail("请先登录"));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        authService.logout(request);
        return ApiResponse.ok(Map.of("success", true));
    }

    private Map<String, Object> userPayload(AuthenticatedUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.id());
        data.put("publicId", AuthService.publicIdFor(user.id()));
        data.put("nickname", user.nickname());
        data.put("account", user.account());
        data.put("role", user.role());
        data.put("status", user.status());
        data.put("displayName", user.nickname());
        return data;
    }

    private String text(Map<String, Object> payload, String key) {
        return String.valueOf(payload.getOrDefault(key, "")).trim();
    }
}
