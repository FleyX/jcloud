package com.fleyx.jcloud.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe 探测支撑组件。
 * <p>
 * 支持本地物理路径与远程文件输入流两种探测方式。
 * <p>
 * 全部字幕流（含 PGS、DVD/DVB 等位图轨）均进入探测结果，位图轨由播放链路按烧录处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaProbeSupport {

    /**
     * 可转换为 WebVTT 的文本字幕编码（与 ffmpeg webvtt 封装支持的文本字幕解码器对应）。
     * PGS（hdmv_pgs_subtitle）、DVD/DVB 等位图字幕不可转文本，消费方经 {@link #isTextSubtitle} 区分。
     */
    private static final Set<String> TEXT_SUBTITLE_CODECS = Set.of(
            "subrip", "ass", "ssa", "mov_text", "text", "webvtt", "sami",
            "microdvd", "mpl2", "pjs", "realtext", "stl", "subviewer",
            "subviewer1", "vplayer", "jacosub");

    private final MediaProperties mediaProperties;
    private final ObjectMapper objectMapper;

    /**
     * 探测本地物理路径的媒体文件。
     *
     * @param path 物理路径
     * @return 探测结果
     */
    public MediaProbeResult probe(Path path) {
        ProcessBuilder builder = new ProcessBuilder(
                mediaProperties.getFfprobePath(),
                "-v", "error",
                "-print_format", "json",
                "-show_format", "-show_streams",
                path.toAbsolutePath().toString());
        builder.redirectErrorStream(false);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(mediaProperties.getProbeTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SystemException(ResultCode.SYSTEM_ERROR, "ffprobe 探测超时: " + path);
            }
            if (process.exitValue() != 0) {
                throw new SystemException(ResultCode.SYSTEM_ERROR, "ffprobe 探测失败: " + path);
            }
            return parse(output);
        } catch (SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "ffprobe 探测异常: " + path, e);
        }
    }

    /**
     * 探测远程文件的媒体信息（通过输入流，只读文件头）。
     *
     * @param inputStream 文件输入流，方法内部关闭
     * @return 探测结果
     */
    public MediaProbeResult probe(InputStream inputStream) {
        ProcessBuilder builder = new ProcessBuilder(
                mediaProperties.getFfprobePath(),
                "-v", "error",
                "-print_format", "json",
                "-show_format", "-show_streams",
                "pipe:0");
        try {
            Process process = builder.start();
            Thread pump = Thread.startVirtualThread(() -> {
                try (inputStream; var out = process.getOutputStream()) {
                    inputStream.transferTo(out);
                } catch (Exception ignored) {
                    // ffprobe 读够数据后关闭管道，忽略 Broken pipe
                }
            });
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(mediaProperties.getProbeTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SystemException(ResultCode.SYSTEM_ERROR, "ffprobe 远程探测超时");
            }
            pump.join(1000);
            if (process.exitValue() != 0) {
                throw new SystemException(ResultCode.SYSTEM_ERROR, "ffprobe 远程探测失败");
            }
            return parse(output);
        } catch (SystemException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "ffprobe 远程探测异常", e);
        }
    }

    MediaProbeResult parse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        Long durationMs = null;
        String container = null;
        Long bitRate = null;
        JsonNode format = root.path("format");
        if (format.isObject()) {
            container = textOrNull(format.path("format_name"));
            if (container != null && container.contains(",")) {
                container = container.split(",")[0];
            }
            String duration = textOrNull(format.path("duration"));
            if (duration != null) {
                durationMs = (long) (Double.parseDouble(duration) * 1000);
            }
            String bitRateText = textOrNull(format.path("bit_rate"));
            if (bitRateText != null) {
                try {
                    bitRate = Long.parseLong(bitRateText);
                } catch (NumberFormatException ignored) {
                    // bit_rate 可能为 N/A 等无法解析的值，忽略
                }
            }
        }

        String videoCodec = null;
        String audioCodec = null;
        Integer width = null;
        Integer height = null;
        List<MediaProbeResult.Track> audioTracks = new ArrayList<>();
        List<MediaProbeResult.Track> subtitleTracks = new ArrayList<>();
        // 原文件字幕流计数：对所有字幕流递增，序号与 ffmpeg -map 0:s:{index} 的原流序号一致
        int subtitleStreamIndex = 0;
        for (JsonNode stream : root.path("streams")) {
            String codecType = textOrNull(stream.path("codec_type"));
            String codec = textOrNull(stream.path("codec_name"));
            if ("video".equals(codecType) && videoCodec == null && !"attached_pic".equals(codec)) {
                videoCodec = codec;
                width = stream.path("width").isInt() ? stream.path("width").asInt() : null;
                height = stream.path("height").isInt() ? stream.path("height").asInt() : null;
            } else if ("audio".equals(codecType)) {
                if (audioCodec == null) {
                    audioCodec = codec;
                }
                audioTracks.add(new MediaProbeResult.Track(
                        audioTracks.size(), codec,
                        textOrNull(stream.path("tags").path("language")),
                        textOrNull(stream.path("tags").path("title")),
                        isDefault(stream)));
            } else if ("subtitle".equals(codecType)) {
                subtitleTracks.add(new MediaProbeResult.Track(
                        subtitleStreamIndex, codec,
                        textOrNull(stream.path("tags").path("language")),
                        textOrNull(stream.path("tags").path("title")),
                        isDefault(stream)));
                subtitleStreamIndex++;
            }
        }
        return new MediaProbeResult(durationMs, container, videoCodec, audioCodec, width, height, bitRate,
                audioTracks, subtitleTracks);
    }

    /**
     * 是否文本字幕编码（可转换为 WebVTT）。位图字幕（PGS、DVD/DVB 等）返回 false。
     *
     * @param codec ffprobe codec_name，null 视为非文本
     * @return true 为文本字幕编码
     */
    public static boolean isTextSubtitle(String codec) {
        return codec != null && TEXT_SUBTITLE_CODECS.contains(codec);
    }

    /**
     * 判断流是否被 ffprobe 标记为默认轨（disposition.default == 1）。
     */
    private boolean isDefault(JsonNode stream) {
        return stream.path("disposition").path("default").asInt(0) == 1;
    }

    private String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
