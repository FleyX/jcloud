package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.util.SubtitleCharsetUtil;
import com.fleyx.jcloud.util.WebVttOffsetUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 字幕转换缓存支撑组件（工单 04 从 MediaSubtitleSupport 拆出）：外部字幕转 webvtt、
 * 转换缓存命名（内容版本）、时间轴偏移结果生成、ffmpeg 转换调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaSubtitleConvertSupport {

    /**
     * 字幕缓存目录（系统空间下），播放链路字幕提取/转换与外部字幕缓存共用（票据 10 起单一定义）。
     */
    public static final String SUBTITLE_CACHE_DIR = "media/subtitles";
    private static final String FORMAT_VTT = "vtt";

    private final MediaProperties mediaProperties;
    private final RemoteFileService remoteFileService;
    private final SystemStorageSpaceProvider systemStorageSpaceProvider;

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
        } catch (java.io.IOException e) {
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
            List<String> command = List.of(mediaProperties.getFfmpegPath(), "-y", "-v", "error",
                    "-i", input.toString(), "-f", "webvtt", target.toString());
            log.info("字幕转换 webvtt: {}", String.join(" ", command));
            Process process = new ProcessBuilder(command).start();
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
