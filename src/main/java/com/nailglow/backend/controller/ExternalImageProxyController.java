package com.nailglow.backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/external-image")
public class ExternalImageProxyController {
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final List<String> ALLOWED_IMAGE_DOMAINS = List.of(
            "xhscdn.com",
            "xiaohongshu.com",
            "meituan.net",
            "meituan.com",
            "dianping.com",
            "dpfile.com",
            "sankuai.com"
    );
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @GetMapping
    public ResponseEntity<byte[]> proxy(@RequestParam String url) {
        URI source = validateSource(url);
        try {
            HttpRequest request = HttpRequest.newBuilder(source)
                    .timeout(Duration.ofSeconds(18))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Referer", refererFor(source.getHost()))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "外站图片返回 " + response.statusCode());
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "外站图片为空");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "外站图片过大");
            }
            MediaType mediaType = mediaTypeFor(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""), source);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .cacheControl(CacheControl.maxAge(6, TimeUnit.HOURS).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(bytes);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "外站图片代理失败");
        }
    }

    private URI validateSource(String rawUrl) {
        if (!StringUtils.hasText(rawUrl) || rawUrl.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片地址无效");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片地址无效");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || !StringUtils.hasText(host)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 HTTP 图片地址");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_IMAGE_DOMAINS.stream().anyMatch(domain ->
                normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该图片域名不允许代理");
        }
        return uri;
    }

    private String refererFor(String host) {
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (normalizedHost.contains("xiaohongshu") || normalizedHost.contains("xhscdn")) {
            return "https://www.xiaohongshu.com/";
        }
        if (normalizedHost.contains("dianping") || normalizedHost.contains("dpfile")) {
            return "https://www.dianping.com/";
        }
        return "https://www.meituan.com/";
    }

    private MediaType mediaTypeFor(String contentType, URI source) {
        String normalized = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/")) {
            return MediaType.parseMediaType(normalized);
        }
        String path = source.getPath() == null ? "" : source.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (path.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (path.endsWith(".svg")) {
            return MediaType.valueOf("image/svg+xml");
        }
        if (path.endsWith(".webp")) {
            return MediaType.valueOf("image/webp");
        }
        return MediaType.IMAGE_PNG;
    }
}
