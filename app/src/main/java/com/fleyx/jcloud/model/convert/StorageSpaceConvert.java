package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import org.mapstruct.Mapper;

/**
 * 存储空间对象转换器。
 */
@Mapper(componentModel = "spring")
public interface StorageSpaceConvert {

    /**
     * 保存 DTO 转 PO。
     *
     * @param dto 保存 DTO
     * @return 存储空间实体
     */
    StorageSpace dtoToPo(StorageSpaceSaveDto dto);

    /**
     * 更新 DTO 转 PO。
     *
     * @param dto 更新 DTO
     * @return 存储空间实体
     */
    StorageSpace updateDtoToPo(StorageSpaceUpdateDto dto);

    /**
     * PO 转 VO。
     *
     * @param po 存储空间实体
     * @return 存储空间视图
     */
    StorageSpaceVo poToVo(StorageSpace po);
}
