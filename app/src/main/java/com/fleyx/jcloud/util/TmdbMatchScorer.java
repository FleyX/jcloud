package com.fleyx.jcloud.util;

import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;

import java.util.List;

/**
 * TMDB 搜索候选打分工具（对齐 Jellyfin 的候选选择策略）。
 * <p>
 * 标题/original_title 归一化比对：完全相同 100 分，互相包含 60 分，其余 0 分；
 * 年份相同 +20、相差 1 年 +10、相差超过 1 年 -30；最高分低于 60 视为不可信匹配。
 */
public final class TmdbMatchScorer {

    /**
     * 可信匹配的最低分数。
     */
    private static final int MATCH_THRESHOLD = 60;

    private TmdbMatchScorer() {
    }

    /**
     * 从候选中选出最优匹配，低于阈值返回 null。
     *
     * @param results TMDB 搜索候选
     * @param title   待匹配标题
     * @param year    待匹配年份，可为 null
     * @return 最优候选，无可信匹配返回 null
     */
    public static TmdbSearchResultVo pickBest(List<TmdbSearchResultVo> results, String title, Integer year) {
        String query = normalizeTitle(title);
        if (query.isEmpty() || results == null || results.isEmpty()) {
            return null;
        }
        TmdbSearchResultVo best = null;
        int bestScore = -1;
        for (TmdbSearchResultVo vo : results) {
            int score = Math.max(titleScore(query, vo.getTitle()), titleScore(query, vo.getOriginalTitle()));
            if (score == 0) {
                continue;
            }
            score += yearScore(year, vo.getReleaseDate());
            if (score > bestScore) {
                bestScore = score;
                best = vo;
            }
        }
        return bestScore >= MATCH_THRESHOLD ? best : null;
    }

    private static int titleScore(String query, String candidate) {
        String c = normalizeTitle(candidate);
        if (c.isEmpty()) {
            return 0;
        }
        if (query.equals(c)) {
            return 100;
        }
        return c.contains(query) || query.contains(c) ? 60 : 0;
    }

    private static int yearScore(Integer year, String releaseDate) {
        if (year == null) {
            return 0;
        }
        Integer releaseYear = parseYear(releaseDate);
        if (releaseYear == null) {
            return 0;
        }
        int diff = Math.abs(releaseYear - year);
        if (diff == 0) {
            return 20;
        }
        return diff == 1 ? 10 : -30;
    }

    /**
     * 归一化标题：小写并去除标点与空白，用于跨语言标题比对。
     */
    static String normalizeTitle(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[\\p{Punct}\\p{Space}]+", "");
    }

    private static Integer parseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(releaseDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
