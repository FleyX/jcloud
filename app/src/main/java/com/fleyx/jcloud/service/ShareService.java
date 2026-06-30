package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.ShareCreateDto;
import com.fleyx.jcloud.model.dto.SharePageQueryDto;
import com.fleyx.jcloud.model.dto.ShareUpdateDto;
import com.fleyx.jcloud.model.vo.ShareDetailVo;
import com.fleyx.jcloud.model.vo.ShareVo;

/**
 * 分享业务接口。
 */
public interface ShareService {

    /**
     * 创建分享。
     *
     * @param dto    创建参数
     * @param userId 当前用户 ID
     * @return 分享视图
     */
    ShareVo create(ShareCreateDto dto, String userId);

    /**
     * 更新分享。
     *
     * @param shareId 分享 ID
     * @param dto     更新参数
     * @param userId  当前用户 ID
     * @return 分享视图
     */
    ShareVo update(String shareId, ShareUpdateDto dto, String userId);

    /**
     * 删除分享（逻辑删除）。
     *
     * @param shareId 分享 ID
     * @param userId  当前用户 ID
     */
    void delete(String shareId, String userId);

    /**
     * 查看分享详情。
     *
     * @param shareId 分享 ID
     * @param userId  当前用户 ID
     * @return 分享详情视图
     */
    ShareDetailVo detail(String shareId, String userId);

    /**
     * 分页查询当前用户的分享列表。
     *
     * @param dto    查询参数
     * @param userId 当前用户 ID
     * @return 分页结果
     */
    IPage<ShareVo> page(SharePageQueryDto dto, String userId);
}
