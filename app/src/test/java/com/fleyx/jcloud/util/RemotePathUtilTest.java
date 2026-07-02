package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 远程路径工具测试。
 */
class RemotePathUtilTest {

    @Test
    void shouldNormalizeRootPath() {
        assertEquals("/webdav", RemotePathUtil.normalizeRootPath("webdav/"));
        assertEquals("/webdav", RemotePathUtil.normalizeRootPath("/webdav/"));
        assertEquals("/webdav", RemotePathUtil.normalizeRootPath("\\webdav\\"));
        assertEquals("", RemotePathUtil.normalizeRootPath(""));
        assertEquals("", RemotePathUtil.normalizeRootPath(null));
    }

    @Test
    void shouldBuildRemotePath() {
        assertEquals("/webdav/docs/report.pdf",
                RemotePathUtil.buildRemotePath("/webdav/", "/docs/report.pdf"));
        assertEquals("/docs/report.pdf",
                RemotePathUtil.buildRemotePath("", "/docs/report.pdf"));
        assertEquals("/webdav",
                RemotePathUtil.buildRemotePath("/webdav/", "/"));
    }

    @Test
    void shouldExtractRelativeNamePath() {
        assertEquals("/docs/report.pdf",
                RemotePathUtil.relativeNamePath("webdav", "/webdav/docs/report.pdf"));
        assertEquals("/",
                RemotePathUtil.relativeNamePath("webdav", "/webdav"));
        assertEquals("/other",
                RemotePathUtil.relativeNamePath("webdav", "/other"));
    }
}
