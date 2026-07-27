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
import java.util.concurrent.TimeUnit;

/**
 * ffprobe 探测支撑组件。
 * <p>
 * 支持本地物理路径与远程文件输入流两种探测方式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaProbeSupport {

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

    private MediaProbeResult parse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        Long durationMs = null;
        String container = null;
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
        }

        String videoCodec = null;
        String audioCodec = null;
        Integer width = null;
        Integer height = null;
        List<MediaProbeResult.Track> audioTracks = new ArrayList<>();
        List<MediaProbeResult.Track> subtitleTracks = new ArrayList<>();
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
                        textOrNull(stream.path("tags").path("title"))));
            } else if ("subtitle".equals(codecType)) {
                subtitleTracks.add(new MediaProbeResult.Track(
                        subtitleTracks.size(), codec,
                        textOrNull(stream.path("tags").path("language")),
                        textOrNull(stream.path("tags").path("title"))));
            }
        }
        return new MediaProbeResult(durationMs, container, videoCodec, audioCodec, width, height, audioTracks, subtitleTracks);
    }

    private String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
