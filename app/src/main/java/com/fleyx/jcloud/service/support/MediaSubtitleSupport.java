package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.model.vo.MediaSubtitleItemVo;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.util.MediaSubtitleNameParser;
import com.fleyx.jcloud.util.SubtitleCharsetUtil;
import com.fleyx.jcloud.util.WebVttOffsetUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 外部字幕支撑组件：扫描关联重建、播放字幕列表组装、字幕读取与转 webvtt。
 * <p>
 * issue #19 起字幕关联对象从旧媒体条目改到文件明细行：file_id 指向
 * t_media_movie_file / t_media_episode_file 明细行或 t_media_other 行（其他库无明细表）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSubtitleSupport {

    /**
     * 字幕缓存目录（系统空间下），播放链路字幕提取/转换与外部字幕缓存共用（票据 10 起单一定义）。
     */
    public static final String SUBTITLE_CACHE_DIR = "media/subtitles";
    private static final String FORMAT_VTT = "vtt";

    private final MediaSubtitleMapper mediaSubtitleMapper;
    private final FileMapper fileMapper;
    private final MediaProperties mediaProperties;
    private final RemoteFileService remoteFileService;
    private final SystemStorageSpaceProvider systemStorageSpaceProvider;

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
        for (FileNode node : nodes) {
            if ("file".equals(node.getType()) && MediaSubtitleNameParser.isSubtitleFile(node.getName())) {
                subtitlesByParent.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
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
                MediaSubtitleNameParser.SubtitleNameMatch match =
                        MediaSubtitleNameParser.parse(videoMainName, sub.getName());
                if (match == null) {
                    continue;
                }
                MediaSubtitle record = new MediaSubtitle();
                record.setFileId(ref.fileRowId());
                record.setFileNodeId(sub.getId());
                record.setFormat(match.format());
                record.setLabel(match.label());
                record.setIsDefault(match.defaulted());
                desired.put(associationKey(ref.fileRowId(), sub.getId()), record);
            }
        }
        return desired;
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
        List<MediaSubtitle> externals = mediaSubtitleMapper.selectList(
                new LambdaQueryWrapper<MediaSubtitle>().eq(MediaSubtitle::getFileId, fileRowId));
        externals.sort(Comparator.comparing((MediaSubtitle s) -> !Boolean.TRUE.equals(s.getIsDefault()))
                .thenComparing(s -> s.getLabel() == null ? "" : s.getLabel()));
        for (MediaSubtitle sub : externals) {
            MediaSubtitleItemVo vo = new MediaSubtitleItemVo();
            vo.setType("external");
            vo.setSubtitleId(sub.getId());
            vo.setDefaulted(Boolean.TRUE.equals(sub.getIsDefault()));
            // 外部位图字幕（.sup/.idx+.sub）识别属于后续工单，本期一律 false
            vo.setBitmap(false);
            vo.setLabel(externalLabel(sub));
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

    /**
     * 解析外部字幕的 webvtt 文件：vtt 原样返回（远程落地缓存），srt/ass/ssa 经 ffmpeg 转换并缓存。
     * <p>
     * 缓存位置 {存储空间}/system/media/subtitles/ext_{fileNodeId}_{内容版本}.vtt，
     * 内容版本优先节点 hash，缺失时回退文件大小与最后修改时间；内容变化后生成新缓存，旧缓存遗留不复用。
     *
     * @param subtitle  外部字幕记录
     * @param node      字幕文件节点
     * @param localPath 本地文件物理路径，远程文件为 null
     * @param userId    用户 ID
     * @return vtt 文件路径
     */
    public Path resolveExternalVtt(MediaSubtitle subtitle, FileNode node, Path localPath, String userId) {
        boolean remote = FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType());
        if (!remote && FORMAT_VTT.equals(subtitle.getFormat())) {
            // 本地 vtt 原样返回字节，无需转换与缓存
            return localPath;
        }
        Path target = Path.of(systemStorageSpaceProvider.getSystemSpace().getPath(),
                "system", SUBTITLE_CACHE_DIR, externalCacheName(node));
        if (Files.exists(target)) {
            return target;
        }
        Path tempInput = null;
        Path tempUtf8 = null;
        try {
            Files.createDirectories(target.getParent());
            Path input = localPath;
            if (remote) {
                // 远程文件先落地临时文件，保证 ffmpeg 可随机访问；保留格式扩展名便于探测
                tempInput = Files.createTempFile("jcloud-sub-ext-", "." + subtitle.getFormat());
                try (InputStream in = remoteFileService.download(node, userId).getInputStream()) {
                    Files.copy(in, tempInput, StandardCopyOption.REPLACE_EXISTING);
                }
                input = tempInput;
            }
            if (FORMAT_VTT.equals(subtitle.getFormat())) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
            // srt/ass/ssa：非 UTF-8 编码先回退 GBK 转 UTF-8 再喂 ffmpeg
            Path utf8Input = SubtitleCharsetUtil.ensureUtf8(input);
            if (!utf8Input.equals(input)) {
                tempUtf8 = utf8Input;
            }
            convertToVtt(utf8Input, target);
            return target;
        } catch (BusinessException | SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "外部字幕读取异常", e);
        } finally {
            deleteQuietly(tempInput);
            deleteQuietly(tempUtf8);
        }
    }

    /**
     * 从规范 VTT 生成独立偏移结果（转码会话时间轴），不改写规范缓存。
     * 偏移结果独立缓存：ext_{fileNodeId}_{内容版本}_off{offsetMs}.vtt。
     *
     * @param node      字幕文件节点
     * @param canonical 规范 VTT 路径（resolveExternalVtt 结果，可能为用户空间本地 vtt）
     * @param offsetMs  转码会话起点（毫秒），调用方已校验非负
     * @return 偏移后的 vtt 文件路径
     */
    public Path resolveOffsetVtt(FileNode node, Path canonical, long offsetMs) {
        String base = externalCacheName(node).replace(".vtt", "") + "_off" + offsetMs;
        Path target = Path.of(systemStorageSpaceProvider.getSystemSpace().getPath(),
                "system", SUBTITLE_CACHE_DIR, base + ".vtt");
        return writeOffsetVtt(canonical, target, offsetMs);
    }

    /**
     * 从内嵌字幕规范 VTT 生成独立偏移结果，不改写规范缓存。
     */
    public Path resolveOffsetVtt(Path canonical, String baseName, long offsetMs) {
        Path target = canonical.getParent().resolve(baseName + "_off" + offsetMs + ".vtt");
        return writeOffsetVtt(canonical, target, offsetMs);
    }

    private Path writeOffsetVtt(Path canonical, Path target, long offsetMs) {
        if (Files.exists(target)) {
            return target;
        }
        try {
            Files.createDirectories(target.getParent());
            String content = Files.readString(canonical, StandardCharsets.UTF_8);
            Files.writeString(target, WebVttOffsetUtil.applyOffset(content, offsetMs), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "字幕时间偏移失败", e);
        }
    }

    /**
     * 外部字幕缓存文件名：ext_{fileNodeId}_{内容版本}.vtt。
     * 内容版本优先节点 hash，缺失时回退文件大小与最后修改时间；均缺失时仅按节点 ID（旧缓存命名）。
     * 本地与远程字幕均适用。
     */
    private String externalCacheName(FileNode node) {
        StringBuilder name = new StringBuilder("ext_").append(node.getId());
        String version = contentVersion(node);
        if (version != null) {
            name.append('_').append(version);
        }
        return name.append(".vtt").toString();
    }

    /**
     * 文件内容版本标识：优先节点 hash（特殊字符安全化），缺失时回退文件大小与最后修改时间。
     */
    private String contentVersion(FileNode node) {
        if (node.getHash() != null && !node.getHash().isBlank()) {
            return node.getHash().replaceAll("[^a-zA-Z0-9\\-_]", "_");
        }
        List<String> parts = new ArrayList<>();
        if (node.getSize() != null) {
            parts.add("size" + node.getSize());
        }
        if (node.getLastModified() != null) {
            parts.add("mtime" + node.getLastModified());
        }
        return parts.isEmpty() ? null : String.join("_", parts);
    }

    private void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 用 ffmpeg 将字幕文件转为 webvtt（参照内嵌字幕提取的 120s 超时）。
     *
     * @param input  输入字幕文件（需为 UTF-8 编码）
     * @param target 输出 vtt 文件
     */
    public void convertToVtt(Path input, Path target) {
        try {
            Process process = new ProcessBuilder(mediaProperties.getFfmpegPath(), "-y", "-v", "error",
                    "-i", input.toString(), "-f", "webvtt", target.toString()).start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SystemException(ResultCode.SYSTEM_ERROR, "字幕转换超时");
            }
            if (process.exitValue() != 0 || !Files.exists(target)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "字幕转换失败，该字幕格式可能不受支持");
            }
        } catch (BusinessException | SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "字幕转换异常", e);
        }
    }
}
