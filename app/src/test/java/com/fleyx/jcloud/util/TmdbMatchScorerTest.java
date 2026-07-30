package com.fleyx.jcloud.util;

import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TMDB 候选打分工具单元测试。
 */
class TmdbMatchScorerTest {

    private TmdbSearchResultVo vo(long tmdbId, String title, String originalTitle, String releaseDate) {
        TmdbSearchResultVo vo = new TmdbSearchResultVo();
        vo.setTmdbId(tmdbId);
        vo.setMediaType("movie");
        vo.setTitle(title);
        vo.setOriginalTitle(originalTitle);
        vo.setReleaseDate(releaseDate);
        return vo;
    }

    @Test
    void testExactTitleMatchWins() {
        List<TmdbSearchResultVo> results = List.of(
                vo(1, "流浪地球", "The Wandering Earth", "2019-02-05"),
                vo(2, "流浪地球2", "The Wandering Earth II", "2023-01-22"));
        TmdbSearchResultVo best = TmdbMatchScorer.pickBest(results, "流浪地球", 2019);
        assertEquals(1, best.getTmdbId());
    }

    @Test
    void testYearBreaksTieBetweenSameTitle() {
        List<TmdbSearchResultVo> results = List.of(
                vo(1, "沙丘", "Dune", "1984-12-01"),
                vo(2, "沙丘", "Dune", "2021-09-03"));
        TmdbSearchResultVo best = TmdbMatchScorer.pickBest(results, "沙丘", 2021);
        assertEquals(2, best.getTmdbId());
    }

    @Test
    void testOriginalTitleMatch() {
        List<TmdbSearchResultVo> results = List.of(
                vo(1, "某某其他", "Something Else", "2020-01-01"),
                vo(2, "瞬息全宇宙", "Everything Everywhere All at Once", "2022-03-11"));
        TmdbSearchResultVo best = TmdbMatchScorer.pickBest(results, "Everything Everywhere All at Once", 2022);
        assertEquals(2, best.getTmdbId());
    }

    @Test
    void testYearMismatchBeyondOneYearRejected() {
        // 仅互相包含（60 分）且年份相差超过 1 年（-30），总分 30 低于阈值
        List<TmdbSearchResultVo> results = List.of(
                vo(1, "庆余年特别篇", "Joy of Life Special", "2010-01-01"));
        assertNull(TmdbMatchScorer.pickBest(results, "庆余年", 2024));
    }

    @Test
    void testNoRelevantCandidateRejected() {
        List<TmdbSearchResultVo> results = List.of(
                vo(1, "完全不相关的电影", "Unrelated", "2020-01-01"));
        assertNull(TmdbMatchScorer.pickBest(results, "庆余年", 2019));
    }

    @Test
    void testNullYearStillMatches() {
        List<TmdbSearchResultVo> results = List.of(
                vo(1, "权力的游戏", "Game of Thrones", "2011-04-17"));
        TmdbSearchResultVo best = TmdbMatchScorer.pickBest(results, "Game of Thrones", null);
        assertEquals(1, best.getTmdbId());
    }

    @Test
    void testBlankTitleRejected() {
        List<TmdbSearchResultVo> results = List.of(vo(1, "沙丘", "Dune", "2021-09-03"));
        assertNull(TmdbMatchScorer.pickBest(results, " ", 2021));
    }
}
