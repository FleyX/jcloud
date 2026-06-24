package com.fleyx.jcloud.util;

import com.fleyx.jcloud.config.JwtProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JwtUtil 单元测试。
 */
class JwtUtilTest {

    private JwtUtil buildJwtUtil() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-must-be-at-least-32-characters-long");
        properties.setIssuer("jcloud-test");
        properties.setExpireHours(1);
        return new JwtUtil(properties);
    }

    @Test
    void shouldGenerateAndParseToken() {
        JwtUtil util = buildJwtUtil();
        String token = util.generateToken(123L, "testUser");
        assertNotNull(token);

        Claims claims = util.parseToken(token);
        assertEquals(123L, util.getUserId(claims));
        assertEquals("testUser", util.getUserCode(claims));
    }

    @Test
    void parseInvalidTokenShouldThrow() {
        JwtUtil util = buildJwtUtil();
        assertThrows(Exception.class, () -> util.parseToken("invalid-token"));
    }

}
