package com.nailglow.backend;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiResponse {
    private ApiResponse() {
    }

    public static Map<String, Object> ok(Object data) {
        return build(0, "ok", data);
    }

    public static Map<String, Object> fail(String message) {
        return build(1, message, null);
    }

    private static Map<String, Object> build(int code, String message, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("message", message);
        result.put("data", data);
        return result;
    }
}
