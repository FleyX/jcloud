package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 转封装会话 ffmpeg 命令构建器。
 * <p>
 * 按流决策：视频编码在直放白名单（h264/hevc/vp9/av1）且未要求降码率、未强制转码时 -c:v copy 转封装，
 * 否则按现有硬解探测路径转码（crf 23 原质量；降码率时叠加 -b:v/-maxrate/-bufsize 与不放大 scale）；
 * 所选音轨编码为 aac/mp3 且（转封装或从头播放）时 -c:a copy，否则统一转 AAC 128k。
 * seek 对齐规则：视频转码时精确 seek 仅裁剪解码流，音频 copy 会停留在关键帧导致音画错位，故转码 + seek 音频必须重编码；
 * 转封装 + seek 时视频停留在关键帧，附加 -noaccurate_seek 保证重编码音频同样从关键帧起步。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeCommandBuilder {

    /**
     * 允许转封装的视频编码白名单（vp8 进 fMP4 兼容性差，排除在外）。
     */
    public static final Set<String> VIDEO_COPY_CODECS = Set.of("h264", "hevc", "vp9", "av1");

    /**
     * 允许转封装的音频编码白名单。
     */
    public static final Set<String> AUDIO_COPY_CODECS = Set.of("aac", "mp3");

    /**
     * 码率档位允许的分辨率高度上限集合。
     */
    public static final Set<Integer> ALLOWED_MAX_HEIGHTS = Set.of(2160, 1080, 720, 480, 360);

    /**
     * 视频转封装决策结果使用的伪编码器标识（会话记录用，非真实编码器）。
     */
    public static final String ENCODER_COPY = "copy";

    private final MediaProperties mediaProperties;

    /**
     * 转码会话请求参数（创建会话时的全部输入）。
     *
     * @param startMs             起始播放位置（毫秒）
     * @param audioIndex          音轨序号，null 表示默认音轨
     * @param localPath           本地物理路径，远程文件为 null
     * @param remoteStream        远程文件输入流提供者，本地文件为 null
     * @param videoCodec          视频编码（probe 结果，缺失为 null）
     * @param audioCodec          所选音轨编码（probe 结果，缺失为 null）
     * @param targetBitrateKbps   目标视频码率上限 kbps，null 表示不降码率
     * @param maxHeight           分辨率高度上限，null 表示不限制
     * @param forceVideoTranscode 是否强制视频转码（前端 MSE 不支持转封装编码时）
     */
    public record TranscodeRequest(long startMs, Integer audioIndex, Path localPath,
                                   Supplier<java.io.InputStream> remoteStream, String videoCodec, String audioCodec,
                                   Long targetBitrateKbps, Integer maxHeight, boolean forceVideoTranscode) {
    }

    /**
     * 校验转码创建参数：码率上限为正数，分辨率上限在档位集合内。
     */
    public static void validateParams(Long targetBitrateKbps, Integer maxHeight) {
        if (targetBitrateKbps != null && targetBitrateKbps <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标码率必须为正数");
        }
        if (maxHeight != null && !ALLOWED_MAX_HEIGHTS.contains(maxHeight)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分辨率上限仅支持 2160/1080/720/480/360");
        }
    }

    /**
     * 视频流是否可转封装（-c:v copy）：编码在白名单、未强制转码、未要求降码率。
     */
    public boolean isVideoCopyEligible(String videoCodec, boolean forceVideoTranscode, Long targetBitrateKbps) {
        return !forceVideoTranscode && targetBitrateKbps == null
                && videoCodec != null && VIDEO_COPY_CODECS.contains(videoCodec.toLowerCase());
    }

    /**
     * 音频流是否可转封装（-c:a copy）：所选音轨编码为 aac/mp3。
     */
    public boolean isAudioCopyEligible(String audioCodec) {
        return audioCodec != null && AUDIO_COPY_CODECS.contains(audioCodec.toLowerCase());
    }

    /**
     * 选择视频编码器：none 或未配置用软解；显式指定直接用对应硬解编码器；auto 按可用性探测，无可用回退软解。
     */
    public String selectEncoder(String configured) {
        if (configured == null || configured.isBlank() || "none".equalsIgnoreCase(configured)) {
            return "libx264";
        }
        if (!"auto".equalsIgnoreCase(configured)) {
            return switch (configured.toLowerCase()) {
                case "vaapi" -> "h264_vaapi";
                case "qsv" -> "h264_qsv";
                case "nvenc" -> "h264_nvenc";
                default -> "libx264";
            };
        }
        // auto：按可用性探测
        for (String candidate : List.of("h264_vaapi", "h264_qsv", "h264_nvenc")) {
            if (encoderAvailable(candidate)) {
                return candidate;
            }
        }
        return "libx264";
    }

    private volatile List<String> availableEncoders;

    private boolean encoderAvailable(String encoder) {
        if (availableEncoders == null) {
            synchronized (this) {
                if (availableEncoders == null) {
                    availableEncoders = detectEncoders();
                }
            }
        }
        return availableEncoders.contains(encoder);
    }

    private List<String> detectEncoders() {
        try {
            Process process = new ProcessBuilder(mediaProperties.getFfmpegPath(), "-hide_banner", "-encoders").start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            List<String> result = new ArrayList<>();
            for (String encoder : List.of("h264_vaapi", "h264_qsv", "h264_nvenc")) {
                if (output.contains(encoder)) {
                    result.add(encoder);
                }
            }
            log.info("可用硬件编码器: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("ffmpeg 编码器探测失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 构建完整 ffmpeg 命令（含输出参数），远程文件输入为 pipe:0。
     *
     * @param request   会话请求
     * @param encoder   视频转码编码器（转封装时传 {@link #ENCODER_COPY}，忽略）
     * @param device    硬件设备路径（仅 vaapi 使用）
     * @param threads   单任务线程数，0 表示自动
     * @param outputDir 会话输出目录
     * @return 命令参数列表
     */
    public List<String> buildCommand(TranscodeRequest request, String encoder, String device, int threads,
                                     Path outputDir) {
        List<String> command = new ArrayList<>();
        command.add(mediaProperties.getFfmpegPath());
        command.addAll(List.of("-hide_banner", "-loglevel", "warning"));
        boolean videoCopy = ENCODER_COPY.equals(encoder);
        if (!videoCopy && "h264_vaapi".equals(encoder)) {
            command.addAll(List.of("-vaapi_device", device));
        }
        if (request.startMs() > 0) {
            command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", request.startMs() / 1000.0)));
            if (videoCopy) {
                // 转封装视频停留在 seek 点前关键帧；关闭精确 seek，让需要重编码的音频同样从关键帧起步，
                // 否则音频被裁到 seek 点而视频早数秒，音画错位
                command.add("-noaccurate_seek");
            }
        }
        command.addAll(List.of("-i",
                request.localPath() != null ? request.localPath().toAbsolutePath().toString() : "pipe:0"));
        command.addAll(List.of("-map", "0:v:0"));
        if (request.audioIndex() != null) {
            command.addAll(List.of("-map", "0:a:" + request.audioIndex()));
        } else {
            command.addAll(List.of("-map", "0:a:0?"));
        }
        if (videoCopy) {
            command.addAll(List.of("-c:v", "copy"));
        } else {
            appendVideoTranscodeArgs(command, request, encoder, threads);
        }
        // 视频转码 + seek 时精确 seek 只裁剪解码流（视频），音频 copy 不参与裁剪会停留在 seek 点前关键帧，
        // 音画错位可达数秒，故此时音频必须重编码随视频一起裁到 seek 点；转封装或从头播放时 copy 即对齐
        boolean audioCopy = isAudioCopyEligible(request.audioCodec()) && (videoCopy || request.startMs() == 0);
        if (audioCopy) {
            command.addAll(List.of("-c:a", "copy"));
        } else {
            command.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-ac", "2"));
        }
        command.addAll(List.of("-f", "hls",
                "-hls_time", String.valueOf(mediaProperties.getHlsSegmentSeconds()),
                "-hls_list_size", "0",
                "-hls_segment_type", "fmp4",
                "-hls_flags", "independent_segments",
                "-hls_segment_filename", outputDir.resolve("seg_%05d.m4s").toString(),
                outputDir.resolve("index.m3u8").toString()));
        return command;
    }

    /**
     * 视频转码参数：保持现有硬解路径与 crf 23 原质量，叠加码率上限与不放大 scale。
     */
    private void appendVideoTranscodeArgs(List<String> command, TranscodeRequest request, String encoder, int threads) {
        command.addAll(List.of("-c:v", encoder));
        String scale = request.maxHeight() != null ? scaleFilter(request.maxHeight()) : null;
        switch (encoder) {
            case "h264_vaapi" ->
                    command.addAll(List.of("-vf", scale != null ? scale + ",format=nv12,hwupload" : "format=nv12,hwupload"));
            case "h264_qsv" ->
                    command.addAll(List.of("-vf", scale != null ? scale + ",format=nv12" : "format=nv12",
                            "-preset", "veryfast", "-global_quality", "23"));
            case "h264_nvenc" -> {
                if (scale != null) {
                    command.addAll(List.of("-vf", scale));
                }
                command.addAll(List.of("-preset", "p4", "-cq", "23"));
            }
            default -> {
                if (scale != null) {
                    command.addAll(List.of("-vf", scale));
                }
                command.addAll(List.of("-preset", "veryfast", "-crf", "23"));
            }
        }
        if (request.targetBitrateKbps() != null) {
            long kbps = request.targetBitrateKbps();
            command.addAll(List.of("-b:v", kbps + "k", "-maxrate", kbps + "k", "-bufsize", 2 * kbps + "k"));
        }
        if (threads > 0) {
            command.addAll(List.of("-threads", String.valueOf(threads)));
        }
    }

    /**
     * 不放大 scale 滤镜：高度降到档位上限以内，宽度按 -2 等比取偶。
     * ffmpeg filter 语法中函数参数的逗号必须转义为 \,（ProcessBuilder 无 shell，逗号会被当作滤镜分隔符）。
     */
    static String scaleFilter(int maxHeight) {
        return "scale=-2:min(" + maxHeight + "\\,ih)";
    }
}
