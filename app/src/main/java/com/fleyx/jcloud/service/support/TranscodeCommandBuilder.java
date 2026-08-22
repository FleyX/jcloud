package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.MediaProperties;
import lombok.RequiredArgsConstructor;
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
 * 按流决策：视频编码在直放白名单（h264/hevc/vp9/av1）且未要求降码率、未强制转码、未携带内嵌或外挂烧录字幕时
 * -c:v copy 转封装，否则按现有硬解探测路径转码（crf 23 原质量；降码率时叠加 -b:v/-maxrate/-bufsize 与不放大 scale）；
 * 携带位图字幕（内嵌轨序号或外挂 .sup/.idx 第二输入）时强制视频转码并在 filter_complex 中 overlay 烧录
 * （见 {@link #buildBurnFilterChain}）。
 * 所选音轨编码为 aac/mp3 且（转封装或从头播放）时 -c:a copy，否则统一转 AAC 128k。
 * seek 对齐规则：视频转码时精确 seek 仅裁剪解码流，音频 copy 会停留在关键帧导致音画错位，故转码 + seek 音频必须重编码；
 * 转封装 + seek 时视频停留在关键帧，附加 -noaccurate_seek 保证重编码音频同样从关键帧起步。
 */
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
     * <p>
     * 携带内嵌位图字幕轨序号（{@code subtitleIndex}）或外挂位图字幕（{@code externalSubtitlePath} /
     * {@code externalSubtitleStream} 任一非空）时强制视频转码：视频流禁止 copy，
     * 转封装会话不可用，位图字幕以 overlay 滤镜烧录进画面（见 {@link #buildCommand}）。
     *
     * @param startMs               起始播放位置（毫秒）
     * @param audioIndex            音轨序号，null 表示默认音轨
     * @param localPath             本地物理路径，远程文件为 null
     * @param remoteStream          远程文件输入流提供者，本地文件为 null
     * @param videoCodec            视频编码（probe 结果，缺失为 null）
     * @param audioCodec            所选音轨编码（probe 结果，缺失为 null）
     * @param targetBitrateKbps     目标视频码率上限 kbps，null 表示不降码率
     * @param maxHeight             分辨率高度上限，null 表示不限制
     * @param forceVideoTranscode   是否强制视频转码（前端 MSE 不支持转封装编码时）
     * @param subtitleIndex         内嵌位图字幕轨序号，null 表示不烧录；携带时强制视频转码
     * @param externalSubtitlePath  外挂位图字幕本地物理路径（.sup/.idx），null 表示无本地外挂
     * @param externalSubtitleStream 远程外挂位图字幕流（.sup/.idx，idx 含 .sub 流），null 表示无远程外挂
     */
    public record TranscodeRequest(long startMs, Integer audioIndex, Path localPath,
                                   Supplier<java.io.InputStream> remoteStream, String videoCodec, String audioCodec,
                                   Long targetBitrateKbps, Integer maxHeight, boolean forceVideoTranscode,
                                   Integer subtitleIndex, Path externalSubtitlePath,
                                   ExternalSubtitleStream externalSubtitleStream) {

        /**
         * 拷贝请求并装入本地外挂字幕路径（远程流物化后由 {@link TranscodeProcessLauncher} 调用，清空流字段避免重复落地）。
         */
        public TranscodeRequest withExternalSubtitlePath(Path path) {
            return new TranscodeRequest(startMs, audioIndex, localPath, remoteStream, videoCodec, audioCodec,
                    targetBitrateKbps, maxHeight, forceVideoTranscode, subtitleIndex, path, null);
        }
    }

    /**
     * 远程外挂位图字幕输入流提供者（本地外挂不适用，直接走 {@link TranscodeRequest#externalSubtitlePath()}）。
     * <p>
     * 会话创建时由 service 装配：{@code subStream} 仅 VobSub（.idx 成对）时非空——.idx 文件不包含像素数据，
     * 必须与同主名的 .sub 一起喂给 ffmpeg；.sup 单文件即完整字幕，{@code subStream} 为 null。
     *
     * @param mainName  字幕主文件名（去扩展名），物化时 .idx/.sub 用相同主名（VobSub demuxer 按同主名找 .sub）
     * @param extension 字幕格式扩展名（sup / idx）
     * @param stream    主字幕文件（.sup 或 .idx）输入流提供者
     * @param subStream .sub 伴生流提供者，仅 idx 成对时非空
     */
    public record ExternalSubtitleStream(String mainName, String extension,
                                         Supplier<java.io.InputStream> stream,
                                         Supplier<java.io.InputStream> subStream) {
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
     * 视频流是否可转封装（-c:v copy）：编码在白名单、未强制转码、未要求降码率、未携带内嵌或外挂烧录字幕。
     * 位图字幕烧录必须逐帧叠加进画面，视频禁止 copy，故内嵌序号或外挂字幕任一非空时恒不可转封装。
     */
    public boolean isVideoCopyEligible(String videoCodec, boolean forceVideoTranscode, Long targetBitrateKbps,
                                       Integer subtitleIndex, Path externalSubtitlePath,
                                       ExternalSubtitleStream externalSubtitleStream) {
        return !forceVideoTranscode && targetBitrateKbps == null && subtitleIndex == null
                && externalSubtitlePath == null && externalSubtitleStream == null
                && videoCodec != null && VIDEO_COPY_CODECS.contains(videoCodec.toLowerCase());
    }

    /**
     * 音频流是否可转封装（-c:a copy）：所选音轨编码为 aac/mp3。
     */
    public boolean isAudioCopyEligible(String audioCodec) {
        return audioCodec != null && AUDIO_COPY_CODECS.contains(audioCodec.toLowerCase());
    }

    /**
     * 选择视频编码器：none / 未配置 / auto / 未知值 → 软解 libx264（auto 仅可能来自 yml 兜底
     * 或探测完成前的极短窗口，安全走软解）；显式指定 vaapi/qsv/nvenc 直接映射对应硬解编码器。
     */
    public String selectEncoder(String configured) {
        if (configured == null || configured.isBlank() || "none".equalsIgnoreCase(configured)
                || "auto".equalsIgnoreCase(configured)) {
            return "libx264";
        }
        return switch (configured.toLowerCase()) {
            case "vaapi" -> "h264_vaapi";
            case "qsv" -> "h264_qsv";
            case "nvenc" -> "h264_nvenc";
            default -> "libx264";
        };
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
        String seek = request.startMs() > 0 ? String.format(Locale.ROOT, "%.3f", request.startMs() / 1000.0) : null;
        if (seek != null) {
            command.addAll(List.of("-ss", seek));
            if (videoCopy) {
                // 转封装视频停留在 seek 点前关键帧；关闭精确 seek，让需要重编码的音频同样从关键帧起步，
                // 否则音频被裁到 seek 点而视频早数秒，音画错位
                command.add("-noaccurate_seek");
            }
        }
        command.addAll(List.of("-i",
                request.localPath() != null ? request.localPath().toAbsolutePath().toString() : "pipe:0"));
        boolean burnExternal = request.externalSubtitlePath() != null || request.externalSubtitleStream() != null;
        if (burnExternal) {
            // 外挂位图字幕作为第二输入：startMs>0 时两个输入前都加 -ss（字幕时间轴随视频一致裁剪，否则错位 startMs），
            // startMs=0 时都不加；音频映射恒取主输入 0:a:*，字幕输入不 map 到输出
            if (seek != null) {
                command.addAll(List.of("-ss", seek));
            }
            command.addAll(List.of("-i", request.externalSubtitlePath().toAbsolutePath().toString()));
        }
        if (request.subtitleIndex() != null || burnExternal) {
            // 烧录路径：视频与字幕 overlay 全部在 filter_complex 内，输出标签 [v] 供 -map 引用；
            // -ss 在 -i 前对视频与字幕流一致裁剪，overlay 时间轴天然与画面对齐，无需额外偏移处理
            command.addAll(List.of("-filter_complex", buildBurnFilterChain(request, encoder, burnExternal)));
            command.addAll(List.of("-map", "[v]"));
        } else {
            command.addAll(List.of("-map", "0:v:0"));
        }
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
     * 烧录路径（内嵌序号或外挂字幕任一非空）的 overlay/scale/hwupload 滤镜已在 filter_complex 内，不再输出 -vf。
     */
    private void appendVideoTranscodeArgs(List<String> command, TranscodeRequest request, String encoder, int threads) {
        command.addAll(List.of("-c:v", encoder));
        boolean burnSubtitle = request.subtitleIndex() != null || request.externalSubtitlePath() != null
                || request.externalSubtitleStream() != null;
        String scale = !burnSubtitle && request.maxHeight() != null ? scaleFilter(request.maxHeight()) : null;
        switch (encoder) {
            case "h264_vaapi" -> {
                if (!burnSubtitle) {
                    command.addAll(List.of("-vf",
                            scale != null ? scale + ",format=nv12,hwupload" : "format=nv12,hwupload"));
                }
            }
            case "h264_qsv" -> {
                if (!burnSubtitle) {
                    command.addAll(List.of("-vf", scale != null ? scale + ",format=nv12" : "format=nv12"));
                }
                command.addAll(List.of("-preset", "veryfast", "-global_quality", "23"));
            }
            case "h264_nvenc" -> {
                // nvenc 仅支持 8bit 输入，10bit 源（如 x265 10bit）不加格式转换会启动失败；
                // 8bit 源时 format=nv12 为 no-op，故非烧录路径无条件附加；烧录路径由 filter_complex 承担
                if (!burnSubtitle) {
                    command.addAll(List.of("-vf", scale != null ? scale + ",format=nv12" : "format=nv12"));
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
     * 烧录滤镜链：主输入视频叠加位图字幕轨，先叠加后缩放（字幕随画面等比缩放），
     * 硬解编码器续接软件帧转码后缀（vaapi 需 format/hwupload 上传），输出标签 [v] 供 -map [v] 引用。
     * 叠加段二选一：内嵌轨从主输入取字幕流（{@code [0:v:0][0:s:N]overlay}），
     * 外挂位图字幕为第二输入（{@code [0:v:0][1:s:0]overlay}）。
     *
     * @param request           会话请求（内嵌序号或外挂字幕任一非空）
     * @param encoder           视频转码编码器
     * @param externalSubtitle  是否外挂位图字幕（第二输入）
     * @return filter_complex 滤镜链字符串
     */
    private String buildBurnFilterChain(TranscodeRequest request, String encoder, boolean externalSubtitle) {
        StringBuilder chain = new StringBuilder("[0:v:0][")
                .append(externalSubtitle ? "1:s:0" : "0:s:" + request.subtitleIndex())
                .append("]overlay");
        if (request.maxHeight() != null) {
            chain.append(',').append(scaleFilter(request.maxHeight()));
        }
        switch (encoder) {
            case "h264_vaapi" -> chain.append(",format=nv12,hwupload");
            // nvenc 与 qsv 同样仅支持 8bit 输入，10bit 源烧录也需转 nv12
            case "h264_qsv", "h264_nvenc" -> chain.append(",format=nv12");
            default -> {
                // 软解无后缀，滤镜即止
            }
        }
        return chain.append("[v]").toString();
    }

    /**
     * 不放大 scale 滤镜：高度降到档位上限以内，宽度按 -2 等比取偶。
     * ffmpeg filter 语法中函数参数的逗号必须转义为 \,（ProcessBuilder 无 shell，逗号会被当作滤镜分隔符）。
     */
    static String scaleFilter(int maxHeight) {
        return "scale=-2:min(" + maxHeight + "\\,ih)";
    }
}
