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
 * 分享访问 Token 工具类。
 * 用于密码校验后签发短期凭证，避免公开访问页重复输入密码。
 */
@Component
@RequiredArgsConstructor
public class ShareTokenUtil {

    private static final String CLAIM_SHARE_CODE = "shareCode";

    private final JwtProperties jwtProperties;

    /**
     * 生成分享访问 Token，默认有效期 1 小时。
     *
     * @param shareCode 分享短码
     * @return JWT Token
     */
    public String generateToken(String shareCode) {
        return generateToken(shareCode, 1);
    }

    /**
     * 生成分享访问 Token。
     *
     * @param shareCode  分享短码
     * @param expireHours 过期小时数
     * @return JWT Token
     */
    public String generateToken(String shareCode, int expireHours) {
        Date now = new Date();
        Date expiration = DateUtil.offsetHour(now, expireHours);
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(shareCode)
                .claim(CLAIM_SHARE_CODE, shareCode)
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
     * 从 Token 中获取分享短码。
     *
     * @param claims 载荷
     * @return 分享短码
     */
    public String getShareCode(Claims claims) {
        return claims.get(CLAIM_SHARE_CODE, String.class);
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
