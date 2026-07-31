package com.fleyx.jcloud.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部字幕文件名解析工具。
 * <p>
 * 匹配规则：字幕文件与视频文件同目录，扩展名为 srt/ass/ssa/vtt（大小写不敏感），
 * 字幕主文件名（去扩展名）等于视频主文件名，或以「视频主文件名 + .」开头（避免 Movie.mkv 误配 Movie2.srt）。
 * 后缀段解析：去掉前缀与扩展名后按 . 分段，含 default 段则为默认字幕，其余段拼为展示标签；
 * 常见语言段做中文映射，映射不到保留原段文本。
 */
public final class MediaSubtitleNameParser {

    /**
     * 支持的字幕扩展名。
     */
    public static final Set<String> SUBTITLE_EXTENSIONS = Set.of("srt", "ass", "ssa", "vtt");

    private static final String DEFAULT_SEGMENT = "default";

    private static final Map<String, String> LANGUAGE_LABELS = Map.ofEntries(
            Map.entry("chs", "简体"), Map.entry("sc", "简体"), Map.entry("zh-cn", "简体"),
            Map.entry("zh", "简体"), Map.entry("hans", "简体"),
            Map.entry("cht", "繁體"), Map.entry("tc", "繁體"), Map.entry("zh-tw", "繁體"),
            Map.entry("hant", "繁體"),
            Map.entry("eng", "English"), Map.entry("en", "English"),
            Map.entry("jpn", "日语"), Map.entry("ja", "日语"));

    private MediaSubtitleNameParser() {
    }

    /**
     * 判断文件名是否为支持的字幕文件。
     */
    public static boolean isSubtitleFile(String fileName) {
        return SUBTITLE_EXTENSIONS.contains(extensionOf(fileName));
    }

    /**
     * 取文件扩展名（小写），无扩展名返回空串。
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        return idx < 0 || idx == fileName.length() - 1 ? "" : fileName.substring(idx + 1).toLowerCase();
    }

    /**
     * 取主文件名（去最后一个扩展名）。
     */
    public static String mainNameOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        return idx <= 0 ? fileName : fileName.substring(0, idx);
    }

    /**
     * 解析字幕文件名与视频主文件名的匹配结果。
     *
     * @param videoMainName  视频主文件名（已去扩展名）
     * @param subtitleName   字幕文件名（含扩展名）
     * @return 匹配结果，不匹配或非字幕文件返回 null
     */
    public static SubtitleNameMatch parse(String videoMainName, String subtitleName) {
        if (!isSubtitleFile(subtitleName) || videoMainName == null || videoMainName.isBlank()) {
            return null;
        }
        String base = mainNameOf(subtitleName);
        String suffix;
        if (base.equals(videoMainName)) {
            suffix = "";
        } else if (base.startsWith(videoMainName + ".")) {
            suffix = base.substring(videoMainName.length() + 1);
        } else {
            return null;
        }
        boolean defaulted = false;
        List<String> labelSegments = new ArrayList<>();
        if (!suffix.isBlank()) {
            for (String segment : suffix.split("\\.")) {
                if (segment.isBlank()) {
                    continue;
                }
                if (DEFAULT_SEGMENT.equalsIgnoreCase(segment)) {
                    defaulted = true;
                } else {
                    labelSegments.add(LANGUAGE_LABELS.getOrDefault(segment.toLowerCase(), segment));
                }
            }
        }
        String label = labelSegments.isEmpty() ? null : String.join(".", labelSegments);
        return new SubtitleNameMatch(extensionOf(subtitleName), label, defaulted);
    }

    /**
     * 字幕文件名匹配结果。
     *
     * @param format    字幕格式（小写扩展名）
     * @param label     展示标签，无后缀段时为 null
     * @param defaulted 是否默认字幕
     */
    public record SubtitleNameMatch(String format, String label, boolean defaulted) {
    }
}
