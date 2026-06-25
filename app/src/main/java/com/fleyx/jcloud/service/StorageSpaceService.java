package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;

/**
 * 存储空间业务接口。
 */
public interface StorageSpaceService {

    /**
     * 新增存储空间。
     *
     * @param dto 保存 DTO
     * @return 存储空间视图
     */
    StorageSpaceVo save(StorageSpaceSaveDto dto);

    /**
     * 分页查询存储空间。
     *
     * @param dto 分页查询条件
     * @return 分页结果
     */
    IPage<StorageSpaceVo> page(StorageSpacePageQueryDto dto);

    /**
     * 更新存储空间。
     *
     * @param dto 更新 DTO
     * @return 更新后的存储空间视图
     */
    StorageSpaceVo update(StorageSpaceUpdateDto dto);

    /**
     * 根据 ID 查询存储空间。
     *
     * @param id 存储空间 ID
     * @return 存储空间视图
     */
    StorageSpaceVo getById(Long id);

    /**
     * 删除存储空间（仅当未被用户绑定时）。
     *
     * @param id 存储空间 ID
     */
    void removeById(Long id);
}
