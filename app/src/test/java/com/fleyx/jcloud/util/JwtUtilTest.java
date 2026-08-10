package com.fleyx.jcloud.util;

import com.fleyx.jcloud.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JwtUtil 单元测试。
 */
class JwtUtilTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-characters-long";

    private JwtUtil buildJwtUtil() {
        return buildJwtUtil(SECRET, 1);
    }

    private JwtUtil buildJwtUtil(String secret, long expireHours) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setIssuer("jcloud-test");
        properties.setExpireHours(expireHours);
        return new JwtUtil(properties);
    }

    @Test
    void shouldGenerateAndParseToken() {
        JwtUtil util = buildJwtUtil();
        String token = util.generateToken("123", "testUser");
        assertNotNull(token);

        Claims claims = util.parseToken(token);
        assertEquals("123", util.getUserId(claims));
        assertEquals("testUser", util.getUserCode(claims));
    }

    @Test
    void parseInvalidTokenShouldThrow() {
        JwtUtil util = buildJwtUtil();
        assertThrows(JwtException.class, () -> util.parseToken("invalid-token"));
    }

    @Test
    void expiredTokenShouldThrowExpiredJwtException() {
        JwtUtil util = buildJwtUtil(SECRET, -1);
        String token = util.generateToken("123", "testUser");

        assertThrows(ExpiredJwtException.class, () -> util.parseToken(token));
    }

    @Test
    void tokenSignedWithDifferentKeyShouldThrowSignatureException() {
        JwtUtil util = buildJwtUtil();
        JwtUtil other = buildJwtUtil("another-secret-key-at-least-32-characters", 1);
        String token = other.generateToken("123", "testUser");

        assertThrows(SignatureException.class, () -> util.parseToken(token));
    }

    @Test
    void tamperedTokenShouldThrowSignatureException() {
        JwtUtil util = buildJwtUtil();
        String token = util.generateToken("123", "testUser");
        String[] parts = token.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"999\",\"userId\":\"999\"}".getBytes(StandardCharsets.UTF_8));

        assertThrows(SignatureException.class,
                () -> util.parseToken(parts[0] + "." + tamperedPayload + "." + parts[2]));
    }

}
