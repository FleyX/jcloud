package com.fleyx.jcloud.util;

import cn.hutool.core.date.DateUtil;
import com.fleyx.jcloud.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类。
 * Token 中仅存放用户 ID 与用户 code，不携带权限等敏感/易变信息。
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProperties;

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USER_CODE = "userCode";

    /**
     * 生成 Token。
     *
     * @param userId   用户 ID
     * @param userCode 用户 code（当前使用 username）
     * @return JWT Token
     */
    public String generateToken(String userId, String userCode) {
        Date now = new Date();
        Date expiration = DateUtil.offsetHour(now, (int) jwtProperties.getExpireHours());
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(userId)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USER_CODE, userCode)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 解析 Token。
     *
     * @param token Token 字符串
     * @return 载荷
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSecretKey())
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 中获取用户 ID。
     *
     * @param claims 载荷
     * @return 用户 ID
     */
    public String getUserId(Claims claims) {
        return claims.get(CLAIM_USER_ID, String.class);
    }

    /**
     * 从 Token 中获取用户 code。
     *
     * @param claims 载荷
     * @return 用户 code
     */
    public String getUserCode(Claims claims) {
        return claims.get(CLAIM_USER_CODE, String.class);
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
