package com.fleyx.jcloud.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.dto.SystemInitDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.SystemInitStatusVo;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.SystemInitService;
import com.fleyx.jcloud.util.DiskSpaceUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统初始化业务实现。
 */
@Service
@RequiredArgsConstructor
public class SystemInitServiceImpl implements SystemInitService {

    private static final String SYSTEM_INITIALIZED_KEY = "system.initialized";
    private static final String SYSTEM_STORAGE_SPACE_ID_KEY = "system.storage.space.id";

    private final StorageSpaceMapper storageSpaceMapper;
    private final UserMapper userMapper;
    private final SystemConfigService systemConfigService;

    @Override
    public SystemInitStatusVo getInitStatus() {
        SystemInitStatusVo vo = new SystemInitStatusVo();
        vo.setInitialized(isInitialized());
        vo.setAdmin(currentUserIsAdmin());
        return vo;
    }

    private boolean currentUserIsAdmin() {
        if (UserContext.get() == null) {
            return false;
        }
        User user = userMapper.selectById(UserContext.get().id());
        return user != null && user.isSuperAdmin();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initialize(SystemInitDto dto) {
        if (isInitialized()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "系统已完成初始化，不能重复初始化");
        }
        List<SystemInitDto.InitSpaceItem> items = dto.getSpaces();
        if (CollUtil.isEmpty(items)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "至少配置一个存储空间");
        }
        int primaryIndex = dto.getPrimaryIndex();
        int systemDataIndex = dto.getSystemDataIndex();
        validateIndex(items, primaryIndex, "主存储空间索引不合法");
        validateIndex(items, systemDataIndex, "系统数据存放空间索引不合法");

        List<Long> spaceIds = new ArrayList<>(items.size());
        for (SystemInitDto.InitSpaceItem item : items) {
            StorageSpace space = buildSpace(item);
            DiskSpaceUtil.refreshSpace(space);
            storageSpaceMapper.insert(space);
            spaceIds.add(space.getId());
        }

        Long primarySpaceId = spaceIds.get(primaryIndex);
        Long systemDataSpaceId = spaceIds.get(systemDataIndex);
        markPrimary(primarySpaceId);

        systemConfigService.setValue(SYSTEM_STORAGE_SPACE_ID_KEY, String.valueOf(systemDataSpaceId));
        systemConfigService.setValue(SYSTEM_INITIALIZED_KEY, "true");

        bindAllUsersToPrimarySpace(primarySpaceId);
    }

    @Override
    public boolean isInitialized() {
        return "true".equalsIgnoreCase(systemConfigService.getValue(SYSTEM_INITIALIZED_KEY, null));
    }

    private void validateIndex(List<SystemInitDto.InitSpaceItem> items, int index, String message) {
        if (index < 0 || index >= items.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        }
    }

    private StorageSpace buildSpace(SystemInitDto.InitSpaceItem item) {
        StorageSpace space = new StorageSpace();
        space.setName(item.getName());
        space.setPath(item.getPath());
        space.setType("USER");
        space.setStatus(1);
        space.setIsPrimary(0);
        space.setRemark(item.getRemark());
        return space;
    }

    private void markPrimary(Long primarySpaceId) {
        LambdaUpdateWrapper<StorageSpace> clearWrapper = new LambdaUpdateWrapper<>();
        clearWrapper.set(StorageSpace::getIsPrimary, 0);
        clearWrapper.eq(StorageSpace::getIsPrimary, 1);
        storageSpaceMapper.update(clearWrapper);

        StorageSpace update = new StorageSpace();
        update.setId(primarySpaceId);
        update.setIsPrimary(1);
        storageSpaceMapper.updateById(update);
    }

    private void bindAllUsersToPrimarySpace(Long primarySpaceId) {
        User update = new User();
        update.setStorageSpaceId(primarySpaceId);
        update.setQuota(0L);
        userMapper.update(update, null);
    }
}
