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

    @Test
    void shouldEncodeSpacesAsPercentTwenty() {
        assertEquals("/Reasons%20to%20use%20Nextcloud.pdf",
                RemotePathUtil.encodePath("/Reasons to use Nextcloud.pdf"));
    }

    @Test
    void shouldEncodeChineseCharacters() {
        assertEquals("/%E6%B5%8B%E8%AF%95/%E6%96%87%E4%BB%B6.pdf",
                RemotePathUtil.encodePath("/测试/文件.pdf"));
    }

    @Test
    void shouldReturnSlashForRootOrBlankPath() {
        assertEquals("/", RemotePathUtil.encodePath("/"));
        assertEquals("/", RemotePathUtil.encodePath(""));
        assertEquals("/", RemotePathUtil.encodePath(null));
    }

    @Test
    void shouldNotOverEncodeAllowedPathCharacters() {
        assertEquals("/~!$&'()*+,;=:@/file.txt",
                RemotePathUtil.encodePath("/~!$&'()*+,;=:@/file.txt"));
    }

    @Test
    void shouldEncodePercentAndReservedCharacters() {
        assertEquals("/%25%3F%23%5B%5D/file.txt",
                RemotePathUtil.encodePath("/%?#[]/file.txt"));
    }
}
