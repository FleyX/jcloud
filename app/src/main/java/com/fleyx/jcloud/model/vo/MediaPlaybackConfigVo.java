package com.fleyx.jcloud.model.vo;

import java.util.List;
import java.util.Set;

/**
 * 全局播放配置视图（ADR 0024）：码率档位阶梯、直放/转封装白名单、看完阈值。
 * <p>
 * 后端常量为唯一事实源，前端进入播放流程时拉取一次并缓存，不再硬编码对应常量。
 *
 * @param bitrateTiers  码率档位阶梯（七档，原画在前）
 * @param directPlay    直放白名单
 * @param remux         转封装白名单
 * @param finishedRatio 看完阈值：进度达到时长的该比例视为看完
 */
public record MediaPlaybackConfigVo(List<BitrateTierVo> bitrateTiers, DirectPlayVo directPlay,
                                    RemuxVo remux, double finishedRatio) {

    /**
     * 码率档位。
     *
     * @param key       档位标识（localStorage 记忆键值）
     * @param label     展示名
     * @param kbps      目标码率上限 kbps，null 表示原画
     * @param maxHeight 分辨率高度上限，null 表示原画
     */
    public record BitrateTierVo(String key, String label, Integer kbps, Integer maxHeight) {
    }

    /**
     * 直放白名单（容器/视频编码/音频编码）。
     */
    public record DirectPlayVo(Set<String> containers, Set<String> videoCodecs, Set<String> audioCodecs) {
    }

    /**
     * 转封装白名单（-c copy 的视频/音频编码）。
     */
    public record RemuxVo(Set<String> videoCopyCodecs, Set<String> audioCopyCodecs) {
    }
}
