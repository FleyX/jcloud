package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件分享收件人记忆支撑类：按登录用户维护最近 5 个收件邮箱（最新在前、去重）。
 * <p>
 * 存储走 RedissonClient 而非 RedisTemplate（ADR 0025），值序列化为 JSON 数组字符串。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailShareRecipientSupport {

    /**
     * Redis key 前缀，完整 key 为 {@code notification:email-share:recipients:{userId}}。
     */
    public static final String KEY_PREFIX = "notification:email-share:recipients:";

    /**
     * 最近收件人保留数量上限。
     */
    public static final int MAX_SIZE = 5;

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /**
     * 合并写入最近收件人：新收件人排在最前，与已有列表去重后截断至 {@link #MAX_SIZE} 个。
     *
     * @param userId     登录用户 ID
     * @param recipients 本次成功发送的收件邮箱，可为空（为空时不改动）
     */
    public void record(String userId, List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        List<String> merged = new ArrayList<>(recipients);
        for (String existing : list(userId)) {
            if (!merged.contains(existing)) {
                merged.add(existing);
            }
        }
        if (merged.size() > MAX_SIZE) {
            merged = merged.subList(0, MAX_SIZE);
        }
        bucket(userId).set(writeJson(merged));
    }

    /**
     * 读取最近收件邮箱（最新在前），无记录或解析失败返回空列表。
     *
     * @param userId 登录用户 ID
     * @return 最近收件邮箱列表
     */
    public List<String> list(String userId) {
        String json = bucket(userId).get();
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> recipients = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return recipients == null ? List.of() : recipients;
        } catch (Exception e) {
            log.warn("最近收件人解析失败，userId={}", userId, e);
            return List.of();
        }
    }

    private RBucket<String> bucket(String userId) {
        return redissonClient.getBucket(KEY_PREFIX + userId, StringCodec.INSTANCE);
    }

    private String writeJson(List<String> recipients) {
        try {
            return objectMapper.writeValueAsString(recipients);
        } catch (Exception e) {
            log.warn("最近收件人序列化失败，recipients={}", recipients, e);
            return "[]";
        }
    }
}
