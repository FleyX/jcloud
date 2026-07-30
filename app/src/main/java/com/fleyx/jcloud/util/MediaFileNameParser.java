package com.fleyx.jcloud.util;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体文件名解析工具。
 * <p>
 * 从视频文件名中解析标题、年份、季号、集号。
 */
public final class MediaFileNameParser {

    /**
     * 支持的视频文件扩展名。
     */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m2ts", "webm", "mpg", "mpeg", "rmvb", "m4v", "3gp", "vob");

    private static final Pattern EPISODE_PATTERN = Pattern.compile(
            "[Ss](\\d{1,2})[Ee](\\d{1,4})|[Ee][Pp]?(\\d{1,4})(?!\\d)|第\\s*(\\d{1,4})\\s*[集话]");
    private static final Pattern SEASON_FOLDER_PATTERN = Pattern.compile(
            "[Ss]eason\\s*(\\d{1,2})|^[Ss](\\d{1,2})$|第\\s*(\\d{1,2}|[零一二三四五六七八九十]{1,3})\\s*季");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(19\\d{2}|20\\d{2})");
    private static final Pattern BRACKET_PATTERN = Pattern.compile("[\\[【(（][^\\]】)）]*[\\]】)）]");
    private static final Pattern QUALITY_PATTERN = Pattern.compile(
            "(?i)(1080p|720p|2160p|4k|8k|blu-?ray|bd|web-?dl|webrip|hdtv|hdr|x264|x265|h\\.?264|h\\.?265|hevc|av1|aac|dts|remux)");
    /**
     * 季号与版本/字幕组标签（对齐 Jellyfin 清洗规则，搜索 TMDB 前剥离）。
     */
    private static final Pattern SEASON_WORD_PATTERN = Pattern.compile(
            "(?i)season\\s*\\d{1,2}|第\\s*(\\d{1,2}|[零一二三四五六七八九十]{1,3})\\s*季"
                    + "|中文字幕|英文字幕|中英字幕|中英双语|国语|粤语|未删减|导演剪辑版?|加长版|修复版|完整版|合集|全集|完结");
    /**
     * 片尾字幕组标记，如 "Movie Name -GRP"。
     */
    private static final Pattern RELEASE_GROUP_PATTERN = Pattern.compile("\\s+-\\s*[A-Za-z0-9]+$");

    private MediaFileNameParser() {
    }

    /**
     * 判断文件名是否为支持的视频文件。
     *
     * @param fileName 文件名
     * @return 是否视频文件
     */
    public static boolean isVideoFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return false;
        }
        return VIDEO_EXTENSIONS.contains(fileName.substring(idx + 1).toLowerCase());
    }

    /**
     * 解析结果。
     *
     * @param title     标题（电影名或剧名）
     * @param year      年份，未解析出为 null
     * @param seasonNo  季号，未解析出为 null
     * @param episodeNo 集号，未解析出为 null
     */
    public record ParseResult(String title, Integer year, Integer seasonNo, Integer episodeNo) {
    }

    /**
     * 解析视频文件名。
     *
     * @param fileName       文件名（含扩展名）
     * @param parentDirName  父目录名，用于季号兜底解析，可为 null
     * @param grandParentDirName 祖父目录名（剧集场景下作为剧名兜底），可为 null
     * @return 解析结果
     */
    public static ParseResult parse(String fileName, String parentDirName, String grandParentDirName) {
        String base = stripExtension(fileName);

        Integer seasonNo = null;
        Integer episodeNo = null;
        Matcher epMatcher = EPISODE_PATTERN.matcher(base);
        String title = base;
        if (epMatcher.find()) {
            if (epMatcher.group(1) != null) {
                seasonNo = Integer.valueOf(epMatcher.group(1));
                episodeNo = Integer.valueOf(epMatcher.group(2));
            } else if (epMatcher.group(3) != null) {
                episodeNo = Integer.valueOf(epMatcher.group(3));
            } else if (epMatcher.group(4) != null) {
                episodeNo = Integer.valueOf(epMatcher.group(4));
            }
            title = base.substring(0, epMatcher.start());
        }

        // 季号从父目录名兜底解析
        if (seasonNo == null && parentDirName != null) {
            Matcher seasonMatcher = SEASON_FOLDER_PATTERN.matcher(parentDirName);
            if (seasonMatcher.find()) {
                for (int i = 1; i <= 3; i++) {
                    if (seasonMatcher.group(i) != null) {
                        seasonNo = parseNumber(seasonMatcher.group(i));
                        break;
                    }
                }
            }
        }
        if (seasonNo == null && episodeNo != null) {
            seasonNo = 1;
        }

        Integer year = null;
        Matcher yearMatcher = YEAR_PATTERN.matcher(base);
        if (yearMatcher.find()) {
            year = Integer.valueOf(yearMatcher.group(1));
        }

        title = cleanTitle(title);
        // 剧集场景：文件名单独解析不出剧名时，用祖父目录（剧文件夹）兜底
        if (episodeNo != null && title.isBlank() && grandParentDirName != null) {
            title = cleanTitle(grandParentDirName);
        }
        return new ParseResult(title, year, seasonNo, episodeNo);
    }

    /**
     * 清理剧文件夹名作为剧名。
     *
     * @param dirName 目录名
     * @return 清理后的名称
     */
    public static String cleanTitle(String dirName) {
        if (dirName == null) {
            return "";
        }
        String cleaned = BRACKET_PATTERN.matcher(dirName).replaceAll(" ");
        cleaned = QUALITY_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = SEASON_WORD_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = YEAR_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replace('.', ' ').replace('_', ' ');
        cleaned = cleaned.trim().replaceAll("\\s{2,}", " ");
        return RELEASE_GROUP_PATTERN.matcher(cleaned).replaceAll("").trim();
    }

    /**
     * 从季文件夹名解析季号。
     *
     * @param folderName 文件夹名
     * @return 季号，无法解析返回 null
     */
    public static Integer parseSeasonNo(String folderName) {
        if (folderName == null) {
            return null;
        }
        Matcher seasonMatcher = SEASON_FOLDER_PATTERN.matcher(folderName);
        if (seasonMatcher.find()) {
            for (int i = 1; i <= 3; i++) {
                if (seasonMatcher.group(i) != null) {
                    return parseNumber(seasonMatcher.group(i));
                }
            }
        }
        return null;
    }

    /**
     * 解析数字，支持阿拉伯数字与中文数字（一到九十九）。
     *
     * @param text 数字文本
     * @return 数字，无法解析返回 null
     */
    static Integer parseNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.matches("\\d+")) {
            return Integer.valueOf(text);
        }
        Map<Character, Integer> digits = Map.of(
                '零', 0, '一', 1, '二', 2, '三', 3, '四', 4,
                '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);
        if (text.length() == 1 && digits.containsKey(text.charAt(0))) {
            return digits.get(text.charAt(0));
        }
        int tenIndex = text.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : digits.getOrDefault(text.charAt(0), 0);
            int ones = tenIndex == text.length() - 1 ? 0 : digits.getOrDefault(text.charAt(text.length() - 1), 0);
            return tens * 10 + ones;
        }
        return null;
    }

    private static String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? fileName : fileName.substring(0, idx);
    }
}
