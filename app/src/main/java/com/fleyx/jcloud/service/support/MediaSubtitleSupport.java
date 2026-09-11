package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.vo.MediaSubtitleItemVo;
import com.fleyx.jcloud.util.MediaSubtitleNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部字幕支撑组件：扫描关联重建、播放字幕列表组装（含纯播放实时探测外挂字幕）。
 * <p>
 * issue #19 起字幕关联对象从旧媒体条目改到文件明细行：file_id 指向
 * t_media_movie_file / t_media_episode_file 明细行或 t_media_other 行（其他库无明细表）。
 * 字幕读取/转 webvtt/偏移缓存见 {@link MediaSubtitleConvertSupport}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSubtitleSupport {

    private final MediaSubtitleMapper mediaSubtitleMapper;
    private final FileMapper fileMapper;

    /**
     * 文件明细行引用（扫描重建字幕关联的入参）：明细行 ID + 锚定的视频文件节点 ID。
     */
    public record FileRef(String fileRowId, String fileNodeId) {
    }

    /**
     * 重建来源目录下各文件明细行的外部字幕关联：按当前文件树重算后与存量记录做 diff，
     * 删除失效、更新变化、插入新增。已存在的关联保持记录 ID 不变，
     * 避免扫描完成时正在播放的页面持有的 subtitleId 失效（404）。
     *
     * @param fileRefs 来源目录当前的文件明细行（电影/集明细行或 other 行）
     * @param nodes    来源目录子树的全部文件节点
     */
    public void rebuildForSource(List<FileRef> fileRefs, List<FileNode> nodes) {
        if (fileRefs.isEmpty()) {
            return;
        }
        List<String> fileRowIds = fileRefs.stream().map(FileRef::fileRowId).toList();
        Map<String, MediaSubtitle> desiredByKey = buildDesiredAssociations(fileRefs, nodes);
        List<MediaSubtitle> existing = mediaSubtitleMapper.selectList(
                new LambdaQueryWrapper<MediaSubtitle>().in(MediaSubtitle::getFileId, fileRowIds));
        int inserted = 0;
        for (MediaSubtitle old : existing) {
            MediaSubtitle want = desiredByKey.remove(associationKey(old.getFileId(), old.getFileNodeId()));
            if (want == null) {
                mediaSubtitleMapper.deleteById(old.getId());
            } else if (associationChanged(old, want)) {
                old.setFormat(want.getFormat());
                old.setLabel(want.getLabel());
                old.setIsDefault(want.getIsDefault());
                mediaSubtitleMapper.updateById(old);
            }
        }
        for (MediaSubtitle record : desiredByKey.values()) {
            mediaSubtitleMapper.insert(record);
            inserted++;
        }
        log.debug("外部字幕关联重建完成: fileRows={}, existing={}, inserted={}", fileRowIds.size(), existing.size(), inserted);
    }

    /**
     * 关联唯一键：与 uk_media_subtitle_file_node 一致（文件明细行 + 字幕文件节点）。
     */
    private String associationKey(String fileRowId, String fileNodeId) {
        return fileRowId + ":" + fileNodeId;
    }

    private boolean associationChanged(MediaSubtitle old, MediaSubtitle want) {
        return !java.util.Objects.equals(old.getFormat(), want.getFormat())
                || !java.util.Objects.equals(old.getLabel(), want.getLabel())
                || !java.util.Objects.equals(old.getIsDefault(), want.getIsDefault());
    }

    /**
     * 按当前文件树计算各文件明细行应有的外部字幕关联，以关联唯一键索引。
     */
    private Map<String, MediaSubtitle> buildDesiredAssociations(List<FileRef> fileRefs, List<FileNode> nodes) {
        Map<String, List<FileNode>> subtitlesByParent = new HashMap<>();
        // 各目录下存在的 .sub 文件主名：位图 .idx 必须与同目录同主名的 .sub 成对，缺一不关联
        Map<String, Set<String>> subMainsByParent = new HashMap<>();
        for (FileNode node : nodes) {
            if (!"file".equals(node.getType())) {
                continue;
            }
            if (MediaSubtitleNameParser.isSubtitleFile(node.getName())
                    || MediaSubtitleNameParser.isBitmapSubtitleFile(node.getName())) {
                subtitlesByParent.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            } else if ("sub".equals(MediaSubtitleNameParser.extensionOf(node.getName()))) {
                subMainsByParent.computeIfAbsent(node.getParentId(), k -> new HashSet<>())
                        .add(MediaSubtitleNameParser.mainNameOf(node.getName()));
            }
        }
        Map<String, FileNode> nodeById = new HashMap<>();
        for (FileNode node : nodes) {
            nodeById.put(node.getId(), node);
        }
        Map<String, MediaSubtitle> desired = new HashMap<>();
        for (FileRef ref : fileRefs) {
            FileNode video = nodeById.get(ref.fileNodeId());
            if (video == null) {
                continue;
            }
            String videoMainName = MediaSubtitleNameParser.mainNameOf(video.getName());
            for (FileNode sub : subtitlesByParent.getOrDefault(video.getParentId(), List.of())) {
                MediaSubtitle record = matchExternalSubtitle(videoMainName, ref.fileRowId(), sub,
                        subMainsByParent.getOrDefault(video.getParentId(), Set.of()));
                if (record != null) {
                    desired.put(associationKey(ref.fileRowId(), sub.getId()), record);
                }
            }
        }
        return desired;
    }

    /**
     * 单视频 × 同目录候选字幕的匹配（扫描重建与纯播放实时探测共用的唯一规则实现）：
     * .idx 必须同目录同主名 .sub 成对（缺则跳过），主名/前缀匹配与 format/label/default 解析走
     * MediaSubtitleNameParser.parse。命中返回合成记录（fileId/fileNodeId/format/label/isDefault），
     * 未命中返回 null。
     */
    private MediaSubtitle matchExternalSubtitle(String videoMainName, String fileRowId, FileNode sub,
                                                Set<String> subMainsInParent) {
        if ("idx".equals(MediaSubtitleNameParser.extensionOf(sub.getName()))
                && !subMainsInParent.contains(MediaSubtitleNameParser.mainNameOf(sub.getName()))) {
            // .idx 缺同目录同主名的 .sub 时跳过（.sub 永远不产生关联，亦回避 MicroDVD 同名嗅探）
            return null;
        }
        MediaSubtitleNameParser.SubtitleNameMatch match =
                MediaSubtitleNameParser.parse(videoMainName, sub.getName());
        if (match == null) {
            return null;
        }
        MediaSubtitle record = new MediaSubtitle();
        record.setFileId(fileRowId);
        record.setFileNodeId(sub.getId());
        record.setFormat(match.format());
        record.setLabel(match.label());
        record.setIsDefault(match.defaulted());
        return record;
    }

    /**
     * 纯播放实时探测外挂字幕（工单 04）：不依赖扫描入库的关联数据，查视频同父目录全部文件节点，
     * 套用与扫描重建同款的前缀匹配规则，返回合成记录（不持久化）。
     * 纯播放语义下 fileRowId=fileNodeId（与 resolvePurePlayable 约定一致）。
     *
     * @param video 视频文件节点
     * @return 命中外挂字幕的合成记录列表
     */
    public List<MediaSubtitle> detectExternalSubtitles(FileNode video) {
        List<FileNode> siblings = fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, video.getParentId())
                .eq(FileNode::getType, "file"));
        List<FileNode> candidates = new ArrayList<>();
        Set<String> subMains = new HashSet<>();
        for (FileNode node : siblings) {
            if (MediaSubtitleNameParser.isSubtitleFile(node.getName())
                    || MediaSubtitleNameParser.isBitmapSubtitleFile(node.getName())) {
                candidates.add(node);
            } else if ("sub".equals(MediaSubtitleNameParser.extensionOf(node.getName()))) {
                subMains.add(MediaSubtitleNameParser.mainNameOf(node.getName()));
            }
        }
        List<MediaSubtitle> detected = new ArrayList<>();
        String videoMainName = MediaSubtitleNameParser.mainNameOf(video.getName());
        for (FileNode sub : candidates) {
            MediaSubtitle record = matchExternalSubtitle(videoMainName, video.getId(), sub, subMains);
            if (record != null) {
                detected.add(record);
            }
        }
        return detected;
    }

    /**
     * 删除文件明细行的外部字幕记录（明细行被清理时一并调用）。
     */
    public void deleteByFileIds(Collection<String> fileRowIds) {
        if (fileRowIds == null || fileRowIds.isEmpty()) {
            return;
        }
        mediaSubtitleMapper.delete(new LambdaQueryWrapper<MediaSubtitle>().in(MediaSubtitle::getFileId, fileRowIds));
    }

    /**
     * 组装播放用的统一字幕列表：内嵌轨在前（按 index），外部在后（默认字幕优先，再按标签）。
     *
     * @param subtitleTracks 实时探测的内嵌字幕轨
     * @param fileRowId      文件明细行 ID（外部字幕关联键）
     * @return 统一字幕列表
     */
    public List<MediaSubtitleItemVo> buildSubtitleList(List<MediaProbeResult.Track> subtitleTracks, String fileRowId) {
        List<MediaSubtitleItemVo> result = buildEmbeddedSubtitleItems(subtitleTracks);
        List<MediaSubtitle> externals = mediaSubtitleMapper.selectList(
                new LambdaQueryWrapper<MediaSubtitle>().eq(MediaSubtitle::getFileId, fileRowId));
        externals.sort(Comparator.comparing((MediaSubtitle s) -> !Boolean.TRUE.equals(s.getIsDefault()))
                .thenComparing(s -> s.getLabel() == null ? "" : s.getLabel()));
        for (MediaSubtitle sub : externals) {
            MediaSubtitleItemVo vo = new MediaSubtitleItemVo();
            vo.setType("external");
            vo.setSubtitleId(sub.getId());
            vo.setDefaulted(Boolean.TRUE.equals(sub.getIsDefault()));
            vo.setBitmap(MediaSubtitleNameParser.isBitmapFormat(sub.getFormat()));
            vo.setLabel(externalLabel(sub));
            result.add(vo);
        }
        return result;
    }

    /**
     * 组装纯播放（by-file-node）的统一字幕列表：内嵌部分与 {@link #buildSubtitleList} 相同，
     * 外挂字幕来自 {@link #detectExternalSubtitles} 实时探测（不读扫描入库的关联数据），
     * 排序规则一致（默认优先、再按标签）；外挂项 subtitleId=字幕文件节点 ID（纯播放语义）。
     *
     * @param subtitleTracks 实时探测的内嵌字幕轨
     * @param video          视频文件节点
     * @return 统一字幕列表
     */
    public List<MediaSubtitleItemVo> buildPureSubtitleList(List<MediaProbeResult.Track> subtitleTracks,
                                                           FileNode video) {
        List<MediaSubtitleItemVo> result = buildEmbeddedSubtitleItems(subtitleTracks);
        List<MediaSubtitle> externals = detectExternalSubtitles(video);
        externals.sort(Comparator.comparing((MediaSubtitle s) -> !Boolean.TRUE.equals(s.getIsDefault()))
                .thenComparing(s -> s.getLabel() == null ? "" : s.getLabel()));
        for (MediaSubtitle sub : externals) {
            MediaSubtitleItemVo vo = new MediaSubtitleItemVo();
            vo.setType("external");
            vo.setSubtitleId(sub.getFileNodeId());
            vo.setDefaulted(Boolean.TRUE.equals(sub.getIsDefault()));
            vo.setBitmap(MediaSubtitleNameParser.isBitmapFormat(sub.getFormat()));
            vo.setLabel(externalLabel(sub));
            result.add(vo);
        }
        return result;
    }

    /**
     * 内嵌字幕轨列表组装（已收录/纯播放共用）：按 index 排列，language/title 拼装展示名，
     * 位图轨（PGS/DVD/DVB 等）标注 bitmap=true。
     */
    private List<MediaSubtitleItemVo> buildEmbeddedSubtitleItems(List<MediaProbeResult.Track> subtitleTracks) {
        List<MediaSubtitleItemVo> result = new ArrayList<>();
        for (MediaProbeResult.Track track : subtitleTracks) {
            MediaSubtitleItemVo vo = new MediaSubtitleItemVo();
            vo.setType("embedded");
            vo.setIndex(track.index());
            vo.setLanguage(track.language());
            vo.setDefaulted(track.defaulted());
            vo.setBitmap(!MediaProbeSupport.isTextSubtitle(track.codec()));
            vo.setLabel(embeddedLabel(track));
            result.add(vo);
        }
        return result;
    }

    /**
     * 内嵌字幕轨展示名：language 与 title 拼装，均缺失时回退「字幕 N」。
     */
    private String embeddedLabel(MediaProbeResult.Track track) {
        List<String> parts = new ArrayList<>();
        if (track.language() != null) {
            parts.add(track.language());
        }
        if (track.title() != null) {
            parts.add(track.title());
        }
        return parts.isEmpty() ? "字幕 " + (track.index() + 1) : String.join(" - ", parts);
    }

    /**
     * 外部字幕展示名：优先解析出的标签，缺失时回退字幕文件主文件名。
     */
    private String externalLabel(MediaSubtitle sub) {
        if (sub.getLabel() != null && !sub.getLabel().isBlank()) {
            return sub.getLabel();
        }
        FileNode node = fileMapper.selectById(sub.getFileNodeId());
        String name = node == null ? null : MediaSubtitleNameParser.mainNameOf(node.getName());
        return name == null || name.isBlank() ? "外部字幕" : name;
    }
}
