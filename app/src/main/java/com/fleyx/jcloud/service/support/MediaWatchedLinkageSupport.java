package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 剧集/季三级联动支撑组件（工单 02）。
 * <p>
 * 维护不变量「父级已观看 ⟺ 其下全部集已观看」：
 * <ul>
 *   <li>{@link #cascadeSeries}/{@link #cascadeSeason}：标记剧/季向下级联所有季与集，置 true 时集进度清零；</li>
 *   <li>{@link #recomputeParents}/{@link #recomputeSeries}：以聚合查询单向重算父级标记，
 *       所有「集集合或集 watched 发生变化」的挂接点统一调用，避免跨分支分散逻辑。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MediaWatchedLinkageSupport {

    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;

    /**
     * 向下级联一季：仅更新该季下所有集的 watched；置 true 时集进度清零（对齐手动标记清零语义）。
     */
    public void cascadeSeason(String seasonId, boolean watched) {
        LambdaUpdateWrapper<MediaEpisode> uw = new LambdaUpdateWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeasonId, seasonId)
                .set(MediaEpisode::getWatched, watched);
        if (watched) {
            uw.set(MediaEpisode::getProgressMs, 0L);
        }
        mediaEpisodeMapper.update(null, uw);
    }

    /**
     * 向下级联一部剧：剧行、所有季与所有集同值；置 true 时集进度清零。
     */
    public void cascadeSeries(String seriesId, boolean watched) {
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, seriesId)
                .set(MediaSeries::getWatched, watched));
        mediaSeasonMapper.update(null, new LambdaUpdateWrapper<MediaSeason>()
                .eq(MediaSeason::getSeriesId, seriesId)
                .set(MediaSeason::getWatched, watched));
        LambdaUpdateWrapper<MediaEpisode> uw = new LambdaUpdateWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, seriesId)
                .set(MediaEpisode::getWatched, watched);
        if (watched) {
            uw.set(MediaEpisode::getProgressMs, 0L);
        }
        mediaEpisodeMapper.update(null, uw);
    }

    /**
     * 单向 roll-up/clear：重算指定季与其所在剧的 watched。
     *
     * @param seasonId 季 ID，可为空（为空仅重算剧，适配「季被删除」场景）
     */
    public void recomputeParents(String seasonId, String seriesId) {
        if (seasonId != null) {
            mediaSeasonMapper.update(null, new LambdaUpdateWrapper<MediaSeason>()
                    .eq(MediaSeason::getId, seasonId)
                    .set(MediaSeason::getWatched, seasonFullyWatched(seasonId)));
        }
        recomputeSeries(seriesId);
    }

    /**
     * 便利重载：仅重算一部剧的 watched（季删除等只影响剧的场景）。
     */
    public void recomputeSeries(String seriesId) {
        Long total = mediaEpisodeMapper.selectCount(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, seriesId));
        Long unwatched = mediaEpisodeMapper.selectCount(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, seriesId).eq(MediaEpisode::getWatched, false));
        mediaSeriesMapper.update(null, new LambdaUpdateWrapper<MediaSeries>()
                .eq(MediaSeries::getId, seriesId)
                .set(MediaSeries::getWatched, fullyWatched(total, unwatched)));
    }

    private boolean seasonFullyWatched(String seasonId) {
        Long total = mediaEpisodeMapper.selectCount(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeasonId, seasonId));
        Long unwatched = mediaEpisodeMapper.selectCount(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeasonId, seasonId).eq(MediaEpisode::getWatched, false));
        return fullyWatched(total, unwatched);
    }

    /**
     * 父级已观看判定：集数 ≥ 1 且全部集已观看。
     */
    private boolean fullyWatched(Long total, Long unwatched) {
        return total != null && total >= 1 && (unwatched == null || unwatched == 0);
    }
}
