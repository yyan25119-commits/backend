package com.nailglow.backend.config;

import com.nailglow.backend.ApiResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public Map<String, Object> responseStatus(ResponseStatusException error) {
        return ApiResponse.fail(error.getReason() == null ? "请求失败" : error.getReason());
    }
}
