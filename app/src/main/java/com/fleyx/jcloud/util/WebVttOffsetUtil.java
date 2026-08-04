package com.fleyx.jcloud.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebVTT cue 时间偏移工具：按转码会话起点把 cue 时间整体前移，得到与 HLS 相对时间轴一致的字幕。
 * <p>
 * 规则（见 docs/prd/media-playback-subtitle-fixes.md）：
 * <ul>
 *   <li>offsetMs 不大于 0 时原样返回（直放不偏移）。</li>
 *   <li>结束时间不晚于偏移量的 cue 完全移除（过期 cue 不输出）。</li>
 *   <li>跨越偏移量的 cue（开始早于、结束晚于偏移量）开始时间钳制到 0。</li>
 *   <li>其余 cue 的开始/结束时间整体减去偏移量。</li>
 * </ul>
 * cue 文本、cue 设置（时间行后的参数）、时间格式（支持 MM:SS.mmm 与 HH:MM:SS.mmm）保持不变。
 */
public final class WebVttOffsetUtil {

    private static final Pattern TIMING_LINE = Pattern.compile(
            "^(\\d{1,3}:\\d{2}:\\d{2}\\.\\d{3}|\\d{1,2}:\\d{2}\\.\\d{3})\\s+-->\\s+"
                    + "(\\d{1,3}:\\d{2}:\\d{2}\\.\\d{3}|\\d{1,2}:\\d{2}\\.\\d{3})(.*)$");

    private static final Pattern MM_TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})\\.(\\d{3})$");
    private static final Pattern HH_TIME = Pattern.compile("^(\\d{1,3}):(\\d{2}):(\\d{2})\\.(\\d{3})$");

    private WebVttOffsetUtil() {
    }

    /**
     * 将 vtt 内容的所有 cue 时间按偏移量调整。
     *
     * @param vtt      webvtt 内容
     * @param offsetMs 转码会话起点（毫秒），不大于 0 时原样返回
     * @return 调整后的 webvtt 内容
     */
    public static String applyOffset(String vtt, long offsetMs) {
        if (vtt == null || offsetMs <= 0) {
            return vtt;
        }
        String[] lines = vtt.split("\n", -1);
        StringBuilder out = new StringBuilder(vtt.length() + 64);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                out.append(line).append('\n');
                i++;
                continue;
            }
            // 文件头（WEBVTT 及头部元信息）原样保留
            if (i == 0 && (line.startsWith("\uFEFFWEBVTT") || line.startsWith("WEBVTT"))) {
                out.append(line).append('\n');
                i++;
                continue;
            }
            // NOTE/STYLE/REGION 块整体保留，不做时间处理
            if (isBlockHeader(line)) {
                while (i < lines.length && !lines[i].isBlank()) {
                    out.append(lines[i]).append('\n');
                    i++;
                }
                continue;
            }
            // cue 块：可选标识符行 + 时间行
            String identifier = null;
            int timingIdx;
            if (isTimingLine(line)) {
                timingIdx = i;
            } else if (i + 1 < lines.length && isTimingLine(lines[i + 1])) {
                identifier = line;
                timingIdx = i + 1;
            } else {
                // 既非块头也非 cue（如头部元信息行），原样保留
                out.append(line).append('\n');
                i++;
                continue;
            }
            int textEnd = timingIdx + 1;
            while (textEnd < lines.length && !lines[textEnd].isBlank()) {
                textEnd++;
            }
            Matcher matcher = TIMING_LINE.matcher(lines[timingIdx]);
            if (!matcher.matches()) {
                for (int k = i; k < textEnd; k++) {
                    out.append(lines[k]).append('\n');
                }
                i = textEnd;
                continue;
            }
            Time start = parseTime(matcher.group(1));
            Time end = parseTime(matcher.group(2));
            String settings = matcher.group(3);
            if (start == null || end == null) {
                for (int k = i; k < textEnd; k++) {
                    out.append(lines[k]).append('\n');
                }
                i = textEnd;
                continue;
            }
            // 结束时间不晚于偏移量的 cue 完全移除
            if (end.ms() <= offsetMs) {
                i = textEnd;
                continue;
            }
            long newStart = Math.max(0, start.ms() - offsetMs);
            long newEnd = end.ms() - offsetMs;
            if (identifier != null) {
                out.append(identifier).append('\n');
            }
            out.append(formatTime(newStart, start.hours()))
                    .append(" --> ").append(formatTime(newEnd, end.hours()))
                    .append(settings).append('\n');
            for (int k = timingIdx + 1; k < textEnd; k++) {
                out.append(lines[k]).append('\n');
            }
            i = textEnd;
        }
        return out.toString();
    }

    /**
     * 判断行是否为 cue 时间行（START --> END [settings]）。
     */
    private static boolean isTimingLine(String line) {
        return TIMING_LINE.matcher(line).matches();
    }

    /**
     * 判断行是否为块头（NOTE/STYLE/REGION 注释与样式块，其内不含 cue）。
     */
    private static boolean isBlockHeader(String line) {
        return line.equals("STYLE") || line.equals("REGION")
                || line.equals("NOTE") || line.startsWith("NOTE ");
    }

    /**
     * 解析单个时间：支持 MM:SS.mmm 与 HH:MM:SS.mmm。
     */
    private static Time parseTime(String text) {
        Matcher mm = MM_TIME.matcher(text);
        if (mm.matches()) {
            long ms = Long.parseLong(mm.group(1)) * 60000
                    + Long.parseLong(mm.group(2)) * 1000
                    + Long.parseLong(mm.group(3));
            return new Time(ms, false);
        }
        Matcher hh = HH_TIME.matcher(text);
        if (hh.matches()) {
            long ms = Long.parseLong(hh.group(1)) * 3600000
                    + Long.parseLong(hh.group(2)) * 60000
                    + Long.parseLong(hh.group(3)) * 1000
                    + Long.parseLong(hh.group(4));
            return new Time(ms, true);
        }
        return null;
    }

    /**
     * 按输入的时间格式输出（小时格式保留 HH:MM:SS.mmm，否则 MM:SS.mmm）。
     */
    private static String formatTime(long ms, boolean hours) {
        long h = ms / 3600000;
        long rem = ms % 3600000;
        long m = rem / 60000;
        long s = (rem % 60000) / 1000;
        long millis = ms % 1000;
        if (hours) {
            return String.format("%02d:%02d:%02d.%03d", h, m, s, millis);
        }
        return String.format("%02d:%02d.%03d", m, s, millis);
    }

    /**
     * 解析后的时间：毫秒值与是否为小时格式。
     */
    private record Time(long ms, boolean hours) {
    }
}
