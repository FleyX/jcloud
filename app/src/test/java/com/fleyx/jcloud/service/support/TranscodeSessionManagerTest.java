package com.fleyx.jcloud.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转码会话播放列表 token 重写测试。
 */
class TranscodeSessionManagerTest {

    @Test
    void shouldAppendTokenToSegmentAndInitMapUris() {
        String playlist = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.000000,
                seg_00000.m4s
                #EXTINF:4.000000,
                seg_00001.m4s
                #EXT-X-ENDLIST
                """;
        String result = TranscodeSessionManager.appendTokenToPlaylist(playlist, "tk.abc");
        assertTrue(result.contains("URI=\"init.mp4?token=tk.abc\""), "EXT-X-MAP URI 应携带 token: " + result);
        assertTrue(result.contains("seg_00000.m4s?token=tk.abc"), "切片 URI 应携带 token: " + result);
        assertTrue(result.contains("seg_00001.m4s?token=tk.abc"), "切片 URI 应携带 token: " + result);
        assertTrue(result.contains("#EXT-X-VERSION:7\n"), "标签行不应被修改: " + result);
        assertTrue(result.contains("#EXT-X-ENDLIST\n") || result.endsWith("#EXT-X-ENDLIST\n"), "结束标签不应被修改");
    }

    @Test
    void shouldKeepAbsoluteAndAlreadyParameterizedUris() {
        String playlist = """
                #EXTM3U
                #EXT-X-MAP:URI="https://cdn.example.com/init.mp4"
                seg_00000.m4s?token=old
                """;
        String result = TranscodeSessionManager.appendTokenToPlaylist(playlist, "tk.abc");
        assertTrue(result.contains("URI=\"https://cdn.example.com/init.mp4\""), "绝对 URI 不应被修改");
        assertTrue(result.contains("seg_00000.m4s?token=old\n") || result.endsWith("seg_00000.m4s?token=old"),
                "已带查询参数的 URI 不应重复追加");
    }

    @Test
    void shouldReturnOriginWhenTokenBlank() {
        String playlist = "#EXTM3U\nseg_00000.m4s\n";
        assertEquals(playlist, TranscodeSessionManager.appendTokenToPlaylist(playlist, null));
        assertEquals(playlist, TranscodeSessionManager.appendTokenToPlaylist(playlist, "  "));
        assertNull(TranscodeSessionManager.appendTokenToPlaylist(null, "tk"));
    }
}
