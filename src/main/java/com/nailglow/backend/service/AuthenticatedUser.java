package com.nailglow.backend.service;

public record AuthenticatedUser(long id, String nickname, String account, String role, String status) {
}
