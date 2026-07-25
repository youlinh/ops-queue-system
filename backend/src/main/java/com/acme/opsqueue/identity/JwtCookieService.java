package com.acme.opsqueue.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class JwtCookieService {
    public static final String COOKIE_NAME = "OPS_SESSION";
    private static final Base64.Encoder BASE64_URL_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final Duration SESSION_DURATION = Duration.ofHours(8);
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final ObjectMapper objectMapper;
    private final byte[] signingKey;
    private final boolean secureCookie;

    public JwtCookieService(
            ObjectMapper objectMapper,
            @Value("${JWT_SIGNING_KEY:}") String signingKey,
            @Value("${JWT_COOKIE_SECURE:true}") boolean secureCookie) {
        if (signingKey == null || signingKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SIGNING_KEY must contain at least 32 UTF-8 bytes");
        }
        this.objectMapper = objectMapper;
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.secureCookie = secureCookie;
    }

    public void issue(HttpServletResponse response, UUID userId) {
        Instant now = Instant.now();
        String header = encode(HEADER.getBytes(StandardCharsets.UTF_8));
        try {
            String payload = encode(objectMapper.writeValueAsBytes(Map.of(
                    "sub", userId.toString(),
                    "iat", now.getEpochSecond(),
                    "exp", now.plus(SESSION_DURATION).getEpochSecond())));
            String content = header + "." + payload;
            String token = content + "." + encode(sign(content));
            addCookie(response, token, SESSION_DURATION);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create session token", exception);
        }
    }

    public Optional<UUID> readUserId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return verify(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    public void clear(HttpServletResponse response) {
        addCookie(response, "", Duration.ZERO);
    }

    private Optional<UUID> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String content = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(sign(content), BASE64_URL_DECODER.decode(parts[2]))) {
                return Optional.empty();
            }
            Map<String, Object> claims = objectMapper.readValue(
                    BASE64_URL_DECODER.decode(parts[1]), new TypeReference<>() {
                    });
            long expiresAt = ((Number) claims.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= expiresAt) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString((String) claims.get("sub")));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }

    private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String encode(byte[] value) {
        return BASE64_URL_ENCODER.encodeToString(value);
    }
}
