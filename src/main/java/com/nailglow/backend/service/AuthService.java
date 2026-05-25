package com.nailglow.backend.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private static final int SESSION_DAYS = 7;
    private static final long PUBLIC_USER_ID_FLOOR = 1_000_000L;
    private final JdbcTemplate jdbc;

    public AuthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> login(String account, String password, String role) {
        String normalizedAccount = normalize(account);
        String normalizedPassword = normalize(password);
        if (normalizedAccount.isBlank() || normalizedPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        }

        List<Map<String, Object>> rows = jdbc.query("""
                select id, nickname, account, role, status, password_hash, try_count, favorite_style
                from users
                where account = ? and role = ?
                limit 1
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("nickname", rs.getString("nickname"));
            row.put("account", rs.getString("account"));
            row.put("role", rs.getString("role"));
            row.put("status", rs.getString("status"));
            row.put("passwordHash", rs.getString("password_hash"));
            row.put("tryCount", rs.getInt("try_count"));
            row.put("favoriteStyle", rs.getString("favorite_style"));
            return row;
        }, normalizedAccount, role);

        if (rows.isEmpty() || !hashPassword(normalizedPassword).equals(rows.get(0).get("passwordHash"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        if ("禁用".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用");
        }

        long userId = ((Number) rows.get(0).get("id")).longValue();
        jdbc.update("update users set last_login_at = current_timestamp where id = ?", userId);
        return buildSessionPayload(rows.get(0), role);
    }

    public Map<String, Object> register(String account, String password, String nickname) {
        String normalizedAccount = normalize(account);
        String normalizedPassword = normalize(password);
        String displayName = normalize(nickname);
        if (normalizedAccount.isBlank() || normalizedPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        }
        if (normalizedPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        Integer exists = jdbc.queryForObject("select count(*) from users where account = ?", Integer.class, normalizedAccount);
        if (exists != null && exists > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
        }
        if (displayName.isBlank()) {
            displayName = normalizedAccount;
        }
        if (displayName.length() < 3 || displayName.length() > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名长度需为 3-8 个字符");
        }

        jdbc.update("""
                insert into users(nickname, account, password_hash, role, status, joined_at, last_login_at, try_count, favorite_style)
                values (?, ?, ?, 'user', '正常', current_timestamp, current_timestamp, 0, null)
                """, displayName, normalizedAccount, hashPassword(normalizedPassword));
        return login(normalizedAccount, normalizedPassword, "user");
    }

    public Optional<AuthenticatedUser> authenticate(HttpServletRequest request) {
        return authenticateToken(bearerToken(request));
    }

    public Optional<AuthenticatedUser> authenticateToken(String token) {
        if (token.isBlank()) {
            return Optional.empty();
        }
        List<AuthenticatedUser> rows = jdbc.query("""
                select u.id, u.nickname, u.account, u.role, u.status
                from auth_sessions s
                join users u on u.id = s.user_id
                where s.token = ?
                  and s.expires_at > current_timestamp
                  and u.status <> '禁用'
                limit 1
                """, (rs, rowNum) -> new AuthenticatedUser(
                rs.getLong("id"),
                rs.getString("nickname"),
                rs.getString("account"),
                rs.getString("role"),
                rs.getString("status")
        ), token);
        return rows.stream().findFirst();
    }

    public AuthenticatedUser require(HttpServletRequest request, String role) {
        AuthenticatedUser user = authenticate(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        if (!role.equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有访问权限");
        }
        return user;
    }

    public void logout(HttpServletRequest request) {
        String token = bearerToken(request);
        if (!token.isBlank()) {
            jdbc.update("delete from auth_sessions where token = ?", token);
        }
    }

    public String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        String fallback = request.getHeader("X-Auth-Token");
        return fallback == null ? "" : fallback.trim();
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Password hash failed", error);
        }
    }

    public static long publicIdFor(long id) {
        if (id >= PUBLIC_USER_ID_FLOOR) {
            return id;
        }
        return PUBLIC_USER_ID_FLOOR + Math.max(0, id - 1);
    }

    private Map<String, Object> buildSessionPayload(Map<String, Object> user, String role) {
        String token = role + "-" + UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(SESSION_DAYS);
        long userId = ((Number) user.get("id")).longValue();
        jdbc.update("""
                insert into auth_sessions(token, user_id, role, expires_at)
                values (?, ?, ?, ?)
                """, token, userId, role, expiresAt);

        Map<String, Object> visibleUser = new LinkedHashMap<>(user);
        visibleUser.remove("passwordHash");
        visibleUser.put("displayName", user.get("nickname"));
        visibleUser.put("publicId", publicIdFor(userId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresAt", expiresAt.toString());
        data.put(role.equals("admin") ? "admin" : "user", visibleUser);
        return data;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
