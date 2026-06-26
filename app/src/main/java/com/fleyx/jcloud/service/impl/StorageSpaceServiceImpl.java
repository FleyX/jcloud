package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.StorageSpaceType;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.StorageSpaceConvert;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.util.DiskSpaceUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 存储空间业务实现。
 */
@Service
@RequiredArgsConstructor
public class StorageSpaceServiceImpl implements StorageSpaceService {

    private final StorageSpaceMapper storageSpaceMapper;
    private final StorageSpaceConvert storageSpaceConvert;
    private final UserMapper userMapper;
    private final SystemConfigService systemConfigService;

    private static final String SYSTEM_STORAGE_SPACE_ID_KEY = "system.storage.space.id";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StorageSpaceVo save(StorageSpaceSaveDto dto) {
        rejectSystemType(dto.getType());
        validateType(dto.getType());
        checkPathUnique(dto.getPath());
        StorageSpace po = storageSpaceConvert.dtoToPo(dto);
        po.setStatus(1);
        DiskSpaceUtil.refreshSpace(po);
        if (Boolean.TRUE.equals(toBoolean(po.getIsPrimary()))) {
            clearOtherPrimary(null);
        }
        storageSpaceMapper.insert(po);
        return storageSpaceConvert.poToVo(po);
    }

    @Override
    public IPage<StorageSpaceVo> page(StorageSpacePageQueryDto dto) {
        LambdaQueryWrapper<StorageSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(dto.getName()), StorageSpace::getName, dto.getName());
        wrapper.eq(StringUtils.hasText(dto.getType()), StorageSpace::getType, dto.getType());
        wrapper.eq(dto.getStatus() != null, StorageSpace::getStatus, dto.getStatus());
        wrapper.orderByDesc(StorageSpace::getCreateTime);

        Page<StorageSpace> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<StorageSpace> poPage = storageSpaceMapper.selectPage(page, wrapper);
        poPage.getRecords().forEach(DiskSpaceUtil::refreshSpace);
        return poPage.convert(storageSpaceConvert::poToVo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StorageSpaceVo update(StorageSpaceUpdateDto dto) {
        StorageSpace existing = storageSpaceMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        rejectSystemType(dto.getType());
        validateType(dto.getType());
        if (!existing.getPath().equals(dto.getPath())) {
            checkPathUnique(dto.getPath());
        }
        boolean becomingPrimary = Boolean.TRUE.equals(toBoolean(dto.getIsPrimary()))
                && !Boolean.TRUE.equals(toBoolean(existing.getIsPrimary()));
        if (becomingPrimary) {
            clearOtherPrimary(dto.getId());
        }
        StorageSpace po = storageSpaceConvert.updateDtoToPo(dto);
        DiskSpaceUtil.refreshSpace(po);
        storageSpaceMapper.updateById(po);
        return storageSpaceConvert.poToVo(storageSpaceMapper.selectById(po.getId()));
    }

    @Override
    public StorageSpaceVo getById(Long id) {
        StorageSpace po = storageSpaceMapper.selectById(id);
        if (po == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        DiskSpaceUtil.refreshSpace(po);
        return storageSpaceConvert.poToVo(po);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeById(Long id) {
        StorageSpace po = storageSpaceMapper.selectById(id);
        if (po == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        rejectIfSystemSpaceConfigured(id);
        rejectIfPrimarySpace(po);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getStorageSpaceId, id);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("该存储空间已被用户绑定，无法删除");
        }
        storageSpaceMapper.deleteById(id);
    }

    @Override
    public StorageSpace getPrimarySpace() {
        LambdaQueryWrapper<StorageSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageSpace::getIsPrimary, 1);
        wrapper.eq(StorageSpace::getStatus, 1);
        wrapper.last("LIMIT 1");
        return storageSpaceMapper.selectOne(wrapper);
    }

    @Override
    public StorageSpaceVo refreshDiskSpace(Long id) {
        StorageSpace po = storageSpaceMapper.selectById(id);
        if (po == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        DiskSpaceUtil.refreshSpace(po);
        storageSpaceMapper.updateById(po);
        return storageSpaceConvert.poToVo(po);
    }

    private void validateType(String type) {
        if (StorageSpaceType.fromCode(type) == null) {
            throw new BusinessException("存储空间类型不合法");
        }
    }

    private void rejectSystemType(String type) {
        if (StorageSpaceType.SYSTEM.getCode().equals(type)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不允许创建或设置为系统类型存储空间，请通过系统目录配置指定");
        }
    }

    private void rejectIfSystemSpaceConfigured(Long id) {
        String configuredId = systemConfigService.getValue(SYSTEM_STORAGE_SPACE_ID_KEY, null);
        if (configuredId != null && configuredId.equals(String.valueOf(id))) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "该存储空间已被指定为系统数据目录，无法删除");
        }
    }

    private void rejectIfPrimarySpace(StorageSpace po) {
        if (Boolean.TRUE.equals(toBoolean(po.getIsPrimary()))) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "主存储空间不能被删除");
        }
    }

    private void checkPathUnique(String path) {
        LambdaQueryWrapper<StorageSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageSpace::getPath, path);
        if (storageSpaceMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("物理路径已存在");
        }
    }

    private void clearOtherPrimary(Long excludeId) {
        LambdaUpdateWrapper<StorageSpace> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(StorageSpace::getIsPrimary, 0);
        wrapper.ne(excludeId != null, StorageSpace::getId, excludeId);
        wrapper.eq(StorageSpace::getIsPrimary, 1);
        storageSpaceMapper.update(wrapper);
    }

    private Boolean toBoolean(Integer value) {
        return Objects.equals(value, 1);
    }
}
