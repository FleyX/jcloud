package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.StorageSpaceType;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 系统存储空间提供者实现。
 * <p>
 * 启动时若不存在 SYSTEM 类型存储空间，则自动创建。
 */
@Component
@RequiredArgsConstructor
public class SystemStorageSpaceProviderImpl implements SystemStorageSpaceProvider {

    private final StorageSpaceMapper storageSpaceMapper;

    @Value("${jcloud.storage.system-path:/data/jcloud/system}")
    private String systemPath;

    @Override
    public StorageSpace getSystemSpace() {
        return findOrCreate();
    }

    private StorageSpace findOrCreate() {
        LambdaQueryWrapper<StorageSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageSpace::getType, StorageSpaceType.SYSTEM.getCode());
        wrapper.eq(StorageSpace::getStatus, 1);
        wrapper.orderByAsc(StorageSpace::getId);
        StorageSpace space = storageSpaceMapper.selectOne(wrapper);
        if (space != null) {
            return space;
        }

        String path = Path.of(systemPath).toAbsolutePath().toString();
        space = new StorageSpace();
        space.setName("系统存储空间");
        space.setPath(path);
        space.setType(StorageSpaceType.SYSTEM.getCode());
        space.setCapacity(Long.MAX_VALUE);
        space.setUsedSpace(0L);
        space.setStatus(1);
        storageSpaceMapper.insert(space);
        return space;
    }
}
