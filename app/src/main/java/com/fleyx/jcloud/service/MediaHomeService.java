package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.vo.MediaHomeVo;

/**
 * 影视首页聚合服务。
 */
public interface MediaHomeService {

    /**
     * 查询影视首页聚合数据（我的媒体 / 继续观看 / 接下来）。
     *
     * @param userId 用户 ID
     * @return 首页聚合视图
     */
    MediaHomeVo getHome(String userId);
}
