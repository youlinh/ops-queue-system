package com.acme.opsqueue.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtCookieServiceTest {
    private static final String KEY =
            "jwt-cookie-unit-test-signing-key-with-at-least-32-bytes";
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtCookieService cookies =
            new JwtCookieService(objectMapper, KEY, true);

    @Test
    void issuedCookieHonorsSecureProductionConfiguration() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.issue(response, UUID.randomUUID());

        assertThat(response.getHeader("Set-Cookie"))
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void rejectsTamperedExpiredAndMalformedClaims() throws Exception {
        UUID userId = UUID.randomUUID();
        String valid = token(Map.of(
                "sub", userId.toString(),
                "exp", Instant.now().plusSeconds(60).getEpochSecond()));
        String[] tokenParts = valid.split("\\.");
        byte[] tamperedSignature = Base64.getUrlDecoder().decode(tokenParts[2]);
        tamperedSignature[0] ^= 0x01;
        String tampered = tokenParts[0]
                + "."
                + tokenParts[1]
                + "."
                + encode(tamperedSignature);

        assertRejected(tampered);
        assertRejected(token(Map.of(
                "sub", userId.toString(),
                "exp", Instant.now().minusSeconds(1).getEpochSecond())));
        assertRejected(token(Map.of("sub", userId.toString())));
        assertRejected(token(Map.of(
                "sub", "not-a-uuid",
                "exp", Instant.now().plusSeconds(60).getEpochSecond())));
        assertRejected(token(Map.of(
                "sub", userId.toString(),
                "exp", "tomorrow")));

        Map<String, Object> missingSubject = new LinkedHashMap<>();
        missingSubject.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
        assertRejected(token(missingSubject));
    }

    private void assertRejected(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JwtCookieService.COOKIE_NAME, token));
        assertThat(cookies.readUserId(request)).isEmpty();
    }

    private String token(Map<String, Object> claims) throws Exception {
        String header = encode("""
                {"alg":"HS256","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8));
        String payload = encode(objectMapper.writeValueAsBytes(claims));
        String content = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return content + "." + encode(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }

    private String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }
}
