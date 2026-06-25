package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.StorageSpaceType;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.StorageSpaceConvert;
import com.fleyx.jcloud.model.dto.StorageSpaceExpandDto;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 存储空间业务实现。
 */
@Service
@RequiredArgsConstructor
public class StorageSpaceServiceImpl implements StorageSpaceService {

    private final StorageSpaceMapper storageSpaceMapper;
    private final StorageSpaceConvert storageSpaceConvert;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StorageSpaceVo save(StorageSpaceSaveDto dto) {
        validateType(dto.getType());
        checkPathUnique(dto.getPath());
        StorageSpace po = storageSpaceConvert.dtoToPo(dto);
        po.setUsedSpace(0L);
        po.setStatus(1);
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
        return poPage.convert(storageSpaceConvert::poToVo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StorageSpaceVo update(StorageSpaceUpdateDto dto) {
        StorageSpace existing = storageSpaceMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        validateType(dto.getType());
        if (!existing.getPath().equals(dto.getPath())) {
            checkPathUnique(dto.getPath());
        }
        StorageSpace po = storageSpaceConvert.updateDtoToPo(dto);
        storageSpaceMapper.updateById(po);
        return storageSpaceConvert.poToVo(storageSpaceMapper.selectById(po.getId()));
    }

    @Override
    public StorageSpaceVo getById(Long id) {
        StorageSpace po = storageSpaceMapper.selectById(id);
        if (po == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        return storageSpaceConvert.poToVo(po);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StorageSpaceVo expandCapacity(StorageSpaceExpandDto dto) {
        StorageSpace existing = storageSpaceMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        long usedSpace = existing.getUsedSpace() == null ? 0L : existing.getUsedSpace();
        if (dto.getCapacity() < usedSpace) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "新容量不能小于已用空间");
        }
        StorageSpace update = new StorageSpace();
        update.setId(existing.getId());
        update.setCapacity(dto.getCapacity());
        storageSpaceMapper.updateById(update);
        return storageSpaceConvert.poToVo(storageSpaceMapper.selectById(update.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeById(Long id) {
        StorageSpace po = storageSpaceMapper.selectById(id);
        if (po == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getStorageSpaceId, id);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("该存储空间已被用户绑定，无法删除");
        }
        storageSpaceMapper.deleteById(id);
    }

    private void validateType(String type) {
        if (StorageSpaceType.fromCode(type) == null) {
            throw new BusinessException("存储空间类型不合法");
        }
    }

    private void checkPathUnique(String path) {
        LambdaQueryWrapper<StorageSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageSpace::getPath, path);
        if (storageSpaceMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("物理路径已存在");
        }
    }
}
