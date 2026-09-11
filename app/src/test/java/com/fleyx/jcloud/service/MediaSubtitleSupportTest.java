package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaSubtitleItemVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯播放外挂字幕实时探测与列表组装测试（工单 04）：
 * 探测命中/不命中、`.default` 后缀识别、idx 缺 .sub 跳过/成对命中、非同主名不命中；
 * 纯播放字幕列表 = 内嵌 + 实时探测外挂且排序正确，全程不读扫描入库的关联表。
 */
@Transactional
class MediaSubtitleSupportTest extends MediaScanTestBase {

    @Autowired
    private MediaSubtitleSupport mediaSubtitleSupport;

    @Autowired
    private MediaSubtitleMapper mediaSubtitleMapper;

    @Autowired
    private FileMapper fileMapper;

    @MockitoBean
    private MediaScrapeService mediaScrapeService;

    /**
     * 实时探测遵循扫描同款前缀匹配规则：同主名/前缀后缀段命中，`.default` 识别为默认字幕，
     * 非同主名（Movie2.srt）不命中，缺 .sub 的孤儿 .idx 跳过，.sup 位图命中。
     */
    @Test
    void shouldDetectExternalSubtitlesByScanSameRules() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "视频");
        upload(user.getId(), folder.getId(), "Movie.mkv");
        upload(user.getId(), folder.getId(), "Movie.chs.srt");
        upload(user.getId(), folder.getId(), "Movie.eng.default.srt");
        upload(user.getId(), folder.getId(), "Movie2.srt");
        upload(user.getId(), folder.getId(), "Movie.orphan.idx");
        upload(user.getId(), folder.getId(), "Movie.cht.sup");

        FileNode video = fileNodeByName("Movie.mkv");
        List<MediaSubtitle> detected = mediaSubtitleSupport.detectExternalSubtitles(video);

        // chs.srt / eng.default.srt / cht.sup 命中；Movie2.srt 与 orphan.idx 不命中
        assertEquals(3, detected.size());
        Map<String, MediaSubtitle> byNode = detected.stream()
                .collect(Collectors.toMap(MediaSubtitle::getFileNodeId, Function.identity()));
        // `.default` 后缀识别：默认字幕 + 语言标签解析
        MediaSubtitle eng = byNode.get(fileNodeByName("Movie.eng.default.srt").getId());
        assertEquals(Boolean.TRUE, eng.getIsDefault());
        assertEquals("English", eng.getLabel());
        assertEquals("srt", eng.getFormat());
        // 纯播放语义：合成记录 fileId=fileNodeId（不持久化，等价 resolvePurePlayable 约定）
        assertEquals(video.getId(), eng.getFileId());
        MediaSubtitle chs = byNode.get(fileNodeByName("Movie.chs.srt").getId());
        assertEquals(Boolean.FALSE, chs.getIsDefault());
        assertEquals("简体", chs.getLabel());
        // 位图外挂（.sup）按 format 标注
        assertEquals("sup", byNode.get(fileNodeByName("Movie.cht.sup").getId()).getFormat());
        // 零读写：探测不触碰字幕关联表
        assertEquals(0, mediaSubtitleMapper.selectCount(null));
    }

    /**
     * .idx 与同目录同主名 .sub 成对时命中（format=idx）；.sub 自身永不作为候选。
     */
    @Test
    void shouldDetectIdxPairWhenSubPresent() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "视频");
        upload(user.getId(), folder.getId(), "Movie.mkv");
        upload(user.getId(), folder.getId(), "Movie.idx");
        upload(user.getId(), folder.getId(), "Movie.sub");

        FileNode video = fileNodeByName("Movie.mkv");
        List<MediaSubtitle> detected = mediaSubtitleSupport.detectExternalSubtitles(video);

        assertEquals(1, detected.size());
        MediaSubtitle vobsub = detected.get(0);
        assertEquals("idx", vobsub.getFormat());
        assertEquals(fileNodeByName("Movie.idx").getId(), vobsub.getFileNodeId());
    }

    /**
     * 纯播放字幕列表：内嵌轨在前（按 index、位图标注），实时探测外挂在后（默认优先、再按标签），
     * 外挂项 subtitleId=字幕文件节点 ID；不读扫描关联表（t_media_subtitle 无记录也可组装）。
     */
    @Test
    void shouldBuildPureSubtitleListWithEmbeddedAndDetectedExternals() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "视频");
        upload(user.getId(), folder.getId(), "沙丘.mkv");
        upload(user.getId(), folder.getId(), "沙丘.chs.srt");
        upload(user.getId(), folder.getId(), "沙丘.eng.default.srt");
        upload(user.getId(), folder.getId(), "沙丘.cht.sup");

        FileNode video = fileNodeByName("沙丘.mkv");
        List<MediaProbeResult.Track> tracks = List.of(
                new MediaProbeResult.Track(0, "subrip", "chi", "简体中文", false),
                new MediaProbeResult.Track(3, "hdmv_pgs_subtitle", "jpn", "PGS", false));

        List<MediaSubtitleItemVo> subtitles = mediaSubtitleSupport.buildPureSubtitleList(tracks, video);

        assertEquals(5, subtitles.size());
        // 内嵌轨在前：按 index 排列，位图轨 bitmap=true
        MediaSubtitleItemVo embedded0 = subtitles.get(0);
        assertEquals("embedded", embedded0.getType());
        assertEquals(0, embedded0.getIndex());
        assertEquals(Boolean.FALSE, embedded0.getBitmap());
        MediaSubtitleItemVo embedded1 = subtitles.get(1);
        assertEquals("embedded", embedded1.getType());
        assertEquals(3, embedded1.getIndex());
        assertEquals(Boolean.TRUE, embedded1.getBitmap());
        // 外挂在后：默认优先、再按标签；外挂项 subtitleId=字幕文件节点 ID（纯播放语义）
        MediaSubtitleItemVo engDefault = subtitles.get(2);
        assertEquals("external", engDefault.getType());
        assertEquals(Boolean.TRUE, engDefault.getDefaulted());
        assertEquals("English", engDefault.getLabel());
        assertEquals(Boolean.FALSE, engDefault.getBitmap());
        assertEquals(fileNodeByName("沙丘.eng.default.srt").getId(), engDefault.getSubtitleId());
        MediaSubtitleItemVo chs = subtitles.get(3);
        assertEquals("简体", chs.getLabel());
        assertEquals(Boolean.FALSE, chs.getDefaulted());
        assertEquals(fileNodeByName("沙丘.chs.srt").getId(), chs.getSubtitleId());
        // 位图外挂（.sup）标注 bitmap=true，不参与自动选中由前端既有逻辑保证
        MediaSubtitleItemVo sup = subtitles.get(4);
        assertEquals("繁體", sup.getLabel());
        assertEquals(Boolean.TRUE, sup.getBitmap());
        assertEquals(fileNodeByName("沙丘.cht.sup").getId(), sup.getSubtitleId());
        // 零读：列表组装全程未读扫描入库的关联表
        assertEquals(0, mediaSubtitleMapper.selectCount(null));
    }

    private FileNode fileNodeByName(String name) {
        return fileMapper.selectOne(new LambdaQueryWrapper<FileNode>().eq(FileNode::getName, name));
    }
}
