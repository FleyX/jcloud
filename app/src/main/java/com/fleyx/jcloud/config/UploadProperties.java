package com.fleyx.jcloud.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上传相关配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jcloud.upload")
public class UploadProperties {

    /**
     * 最小分片大小：1MB。
     */
    public static final long MIN_CHUNK_SIZE = 1024 * 1024;

    /**
     * 最大分片大小：512MB。
     */
    public static final long MAX_CHUNK_SIZE = 512L * 1024 * 1024;

    /**
     * 分片大小（字节），默认 64MB。
     */
    private long chunkSize = 64L * 1024 * 1024;

    /**
     * 分片上传临时目录保留时长（小时），超期无活动的残留目录由定时清理任务删除，默认 24 小时。
     */
    private long tempRetentionHours = 24;

    /**
     * 校验分片大小配置是否合法。
     */
    @PostConstruct
    public void validate() {
        if (chunkSize <= 0) {
            throw new IllegalStateException("jcloud.upload.chunk-size 必须大于 0");
        }
        if (chunkSize < MIN_CHUNK_SIZE) {
            throw new IllegalStateException("jcloud.upload.chunk-size 不能小于 1MB");
        }
        if (chunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalStateException("jcloud.upload.chunk-size 不能大于 512MB");
        }
    }
}
