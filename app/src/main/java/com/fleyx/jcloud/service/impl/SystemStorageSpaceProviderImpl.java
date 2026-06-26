package com.fleyx.jcloud.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.StorageSpaceType;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 系统存储空间提供者实现。
 * <p>
 * 系统数据目录不再使用独立的 SYSTEM 类型存储空间，而是挂载在选定的用户存储空间下的 /system 子目录。
 */
@Component
@RequiredArgsConstructor
public class SystemStorageSpaceProviderImpl implements SystemStorageSpaceProvider {

    private static final String SYSTEM_STORAGE_SPACE_ID_KEY = "system.storage.space.id";
    private static final String SYSTEM_SUB_DIRECTORY = "system";

    private final SystemConfigService systemConfigService;
    private final StorageSpaceMapper storageSpaceMapper;

    @Override
    public StorageSpace getSystemSpace() {
        String spaceId = systemConfigService.getValue(SYSTEM_STORAGE_SPACE_ID_KEY, null);
        if (StrUtil.isBlank(spaceId)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "系统数据目录未配置，请先配置用户存储空间");
        }
        StorageSpace space = storageSpaceMapper.selectById(parseSpaceId(spaceId));
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "系统数据目录配置的存储空间不存在");
        }
        if (!StorageSpaceType.USER.getCode().equals(space.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "系统数据目录必须配置为用户存储空间");
        }
        return buildSystemSpace(space);
    }

    private Long parseSpaceId(String spaceId) {
        try {
            return Long.valueOf(spaceId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "系统数据目录配置的空间 ID 不合法");
        }
    }

    private StorageSpace buildSystemSpace(StorageSpace userSpace) {
        StorageSpace systemSpace = new StorageSpace();
        systemSpace.setId(userSpace.getId());
        systemSpace.setName(userSpace.getName());
        systemSpace.setPath(Path.of(userSpace.getPath()).resolve(SYSTEM_SUB_DIRECTORY).toString());
        systemSpace.setType(StorageSpaceType.USER.getCode());
        systemSpace.setCapacity(userSpace.getCapacity());
        systemSpace.setUsedSpace(userSpace.getUsedSpace());
        systemSpace.setStatus(userSpace.getStatus());
        systemSpace.setRemark(userSpace.getRemark());
        return systemSpace;
    }
}
