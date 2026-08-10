package com.fleyx.jcloud.util;

import com.fleyx.jcloud.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShareTokenUtil 分享访问 Token 工具类测试。
 */
class ShareTokenUtilTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-characters-long";

    private ShareTokenUtil buildUtil() {
        return buildUtil("jcloud-test");
    }

    private ShareTokenUtil buildUtil(String issuer) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer(issuer);
        return new ShareTokenUtil(properties);
    }

    @Test
    void shouldGenerateAndParseToken() {
        ShareTokenUtil util = buildUtil();
        String token = util.generateToken("share-abc-123");
        assertNotNull(token);

        Claims claims = util.parseToken(token);
        assertEquals("share-abc-123", util.getShareCode(claims));
        assertEquals("share-abc-123", claims.getSubject());
    }

    @Test
    void defaultTokenShouldExpireInAboutOneHour() {
        ShareTokenUtil util = buildUtil();
        Claims claims = util.parseToken(util.generateToken("share-abc"));

        assertDurationAbout(claims, 3_600_000L);
    }

    @Test
    void tokenWithCustomExpireHoursShouldExpireInAboutTwoHours() {
        ShareTokenUtil util = buildUtil();
        Claims claims = util.parseToken(util.generateToken("share-abc", 2));

        assertDurationAbout(claims, 7_200_000L);
    }

    @Test
    void tamperedTokenShouldThrowSignatureException() {
        ShareTokenUtil util = buildUtil();
        String token = util.generateToken("share-abc");
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');

        assertThrows(SignatureException.class, () -> util.parseToken(tampered));
    }

    @Test
    void tokenParsedWithDifferentIssuerShouldThrowJwtException() {
        ShareTokenUtil util = buildUtil();
        ShareTokenUtil other = buildUtil("jcloud-other");
        String token = util.generateToken("share-abc");

        assertThrows(JwtException.class, () -> other.parseToken(token));
    }

    @Test
    void tokenWithNegativeExpireHoursShouldBeExpired() {
        ShareTokenUtil util = buildUtil();
        String token = util.generateToken("share-abc", -1);

        assertThrows(ExpiredJwtException.class, () -> util.parseToken(token));
    }

    private static void assertDurationAbout(Claims claims, long expectedMillis) {
        long durationMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertTrue(durationMillis >= expectedMillis - 5_000L, "duration=" + durationMillis);
        assertTrue(durationMillis <= expectedMillis + 5_000L, "duration=" + durationMillis);
    }
}
