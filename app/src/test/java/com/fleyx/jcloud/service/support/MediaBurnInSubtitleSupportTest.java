package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.service.RemoteFileService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 外挂位图字幕烧录装配测试（工单 04 纯播放 by-file-node 变体）：
 * 探测命中校验、位图格式校验（文本拒绝）、节点归属、本地装路径、远程装流（idx 成对缺 .sub 拒绝）；
 * 已收录记录入口的共用装配段一并守护。
 */
class MediaBurnInSubtitleSupportTest {

    private final MediaSubtitleMapper mediaSubtitleMapper = mock(MediaSubtitleMapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final RemoteFileService remoteFileService = mock(RemoteFileService.class);
    private final MediaSubtitleSupport mediaSubtitleSupport = mock(MediaSubtitleSupport.class);

    private final MediaBurnInSubtitleSupport support = new MediaBurnInSubtitleSupport(
            mediaSubtitleMapper, fileMapper, remoteFileService, mediaSubtitleSupport);

    private FileNode node(String id, String userId, String name, String sourceType) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(userId);
        node.setParentId("dir-1");
        node.setName(name);
        node.setSourceType(sourceType);
        return node;
    }

    private MediaSubtitle detected(String fileNodeId, String format) {
        MediaSubtitle subtitle = new MediaSubtitle();
        subtitle.setFileId("fn-1");
        subtitle.setFileNodeId(fileNodeId);
        subtitle.setFormat(format);
        return subtitle;
    }

    @Test
    void shouldReturnEmptyWhenExternalSubtitleIdNull() {
        FileNode video = node("fn-1", "user-1", "movie.mkv", "local");

        MediaBurnInSubtitleSupport.ExternalSubtitleBurn result =
                support.resolveExternalSubtitleBurnByFileNode(video, null, "user-1", n -> null);

        assertNull(result.path());
        assertNull(result.stream());
        verifyNoInteractions(mediaSubtitleSupport);
    }

    @Test
    void shouldRejectByFileNodeExternalSubtitleNotDetected() {
        FileNode video = node("fn-1", "user-1", "movie.mkv", "local");
        when(mediaSubtitleSupport.detectExternalSubtitles(video)).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.resolveExternalSubtitleBurnByFileNode(video, "sub-1", "user-1", n -> null));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        assertEquals("字幕不存在", exception.getMessage());
        verifyNoInteractions(fileMapper);
    }

    @Test
    void shouldRejectByFileNodeTextSubtitleBurn() {
        FileNode video = node("fn-1", "user-1", "movie.mkv", "local");
        when(mediaSubtitleSupport.detectExternalSubtitles(video))
                .thenReturn(List.of(detected("sub-1", "srt")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.resolveExternalSubtitleBurnByFileNode(video, "sub-1", "user-1", n -> null));

        assertEquals(ResultCode.PARAM_ERROR, exception.getResultCode());
        assertEquals("文本字幕无需烧录", exception.getMessage());
    }

    @Test
    void shouldAssembleLocalExternalBurnByFileNode() {
        FileNode video = node("fn-1", "user-1", "movie.mkv", "local");
        FileNode subNode = node("sub-1", "user-1", "movie.cht.sup", "local");
        when(mediaSubtitleSupport.detectExternalSubtitles(video))
                .thenReturn(List.of(detected("sub-1", "sup")));
        when(fileMapper.selectById("sub-1")).thenReturn(subNode);
        Path subPath = Path.of("/data/user-1/movie.cht.sup");

        MediaBurnInSubtitleSupport.ExternalSubtitleBurn result =
                support.resolveExternalSubtitleBurnByFileNode(video, "sub-1", "user-1", n -> subPath);

        assertEquals(subPath, result.path());
        assertNull(result.stream());
    }

    @Test
    void shouldRejectByFileNodeSubtitleOwnedByOthers() {
        FileNode video = node("fn-1", "user-1", "movie.mkv", "local");
        when(mediaSubtitleSupport.detectExternalSubtitles(video))
                .thenReturn(List.of(detected("sub-1", "sup")));
        when(fileMapper.selectById("sub-1")).thenReturn(node("sub-1", "other-user", "movie.cht.sup", "local"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.resolveExternalSubtitleBurnByFileNode(video, "sub-1", "user-1", n -> null));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        assertEquals("字幕文件不存在", exception.getMessage());
    }

    @Test
    void shouldAssembleRemoteIdxBurnWithSubPair() {
        FileNode video = node("fn-1", "user-1", "movie.mkv", "local");
        FileNode idxNode = node("sub-1", "user-1", "movie.cht.idx", "remote");
        FileNode subFileNode = node("sub-2", "user-1", "movie.cht.sub", "remote");
        when(mediaSubtitleSupport.detectExternalSubtitles(video))
                .thenReturn(List.of(detected("sub-1", "idx")));
        when(fileMapper.selectById("sub-1")).thenReturn(idxNode);
        when(fileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(subFileNode);
        when(remoteFileService.download(any(FileNode.class), any()))
                .thenAnswer(invocation -> new FileDownloadResult(
                        ((FileNode) invocation.getArgument(0)).getName(),
                        new ByteArrayInputStream(new byte[0]), "application/octet-stream", 10L));

        MediaBurnInSubtitleSupport.ExternalSubtitleBurn result =
                support.resolveExternalSubtitleBurnByFileNode(video, "sub-1", "user-1", n -> null);

        assertNull(result.path());
        assertNotNull(result.stream());
        assertEquals("movie.cht", result.stream().mainName());
        assertEquals("idx", result.stream().extension());
        // supplier 真实取流：idx 主文件 + 成对 .sub 各下载一次
        assertNotNull(result.stream().stream().get());
        assertNotNull(result.stream().subStream().get());
        verify(remoteFileService).download(idxNode, "user-1");
        verify(remoteFileService).download(subFileNode, "user-1");
    }

    @Test
    void shouldRejectRemoteIdxBurnWhenSubMissing() {
        FileNode video = node("fn-1", "user-1", "movie.mkv", "local");
        FileNode idxNode = node("sub-1", "user-1", "movie.cht.idx", "remote");
        when(mediaSubtitleSupport.detectExternalSubtitles(video))
                .thenReturn(List.of(detected("sub-1", "idx")));
        when(fileMapper.selectById("sub-1")).thenReturn(idxNode);
        when(fileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.resolveExternalSubtitleBurnByFileNode(video, "sub-1", "user-1", n -> null));

        assertEquals(ResultCode.BUSINESS_ERROR, exception.getResultCode());
        assertEquals("VobSub 字幕缺少 .sub 文件", exception.getMessage());
    }

    @Test
    void shouldRejectRecordPathTextSubtitleBurn() {
        // 已收录记录入口：共用装配段的格式校验保持一致
        MediaSubtitle record = new MediaSubtitle();
        record.setId("ms-1");
        record.setFileId("row-1");
        record.setFileNodeId("sub-1");
        record.setFormat("srt");
        when(mediaSubtitleMapper.selectById("ms-1")).thenReturn(record);
        MediaPlaybackResolveSupport.Playable playable =
                new MediaPlaybackResolveSupport.Playable(
                        "row-1", "fn-1", 1_024L, null, null, null, null, null, null, 0L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> support.resolveExternalSubtitleBurn(playable, "ms-1", "user-1", n -> null));

        assertEquals(ResultCode.PARAM_ERROR, exception.getResultCode());
        assertEquals("文本字幕无需烧录", exception.getMessage());
    }
}
