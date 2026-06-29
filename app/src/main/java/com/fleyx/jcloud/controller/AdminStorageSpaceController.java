package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.dto.SystemStorageConfigUpdateDto;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.SystemStorageConfigVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.SystemConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储空间管理控制器（管理员）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/admin/storage-spaces")
@RequiredArgsConstructor
public class AdminStorageSpaceController {

    private final StorageSpaceService storageSpaceService;
    private final SystemConfigService systemConfigService;

    private static final String SYSTEM_STORAGE_SPACE_ID_KEY = "system.storage.space.id";

    /**
     * 新增存储空间。
     */
    @PostMapping
    public R<StorageSpaceVo> save(@Valid @RequestBody StorageSpaceSaveDto dto) {
        return R.ok(storageSpaceService.save(dto));
    }

    /**
     * 分页查询存储空间。
     */
    @GetMapping
    public R<IPage<StorageSpaceVo>> page(StorageSpacePageQueryDto dto) {
        return R.ok(storageSpaceService.page(dto));
    }

    /**
     * 根据 ID 查询存储空间。
     */
    @GetMapping("/{id}")
    public R<StorageSpaceVo> getById(@PathVariable String id) {
        return R.ok(storageSpaceService.getById(id));
    }

    /**
     * 更新存储空间。
     */
    @PutMapping("/{id}")
    public R<StorageSpaceVo> update(@PathVariable String id, @Valid @RequestBody StorageSpaceUpdateDto dto) {
        dto.setId(id);
        return R.ok(storageSpaceService.update(dto));
    }

    /**
     * 删除存储空间。
     */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable String id) {
        storageSpaceService.removeById(id);
        return R.ok();
    }

    /**
     * 刷新存储空间磁盘状态。
     */
    @PostMapping("/{id}/refresh")
    public R<StorageSpaceVo> refreshDiskSpace(@PathVariable String id) {
        return R.ok(storageSpaceService.refreshDiskSpace(id));
    }

    /**
     * 查询系统数据目录配置。
     */
    @GetMapping("/system-config")
    public R<SystemStorageConfigVo> getSystemConfig() {
        SystemStorageConfigVo vo = new SystemStorageConfigVo();
        vo.setSystemSpaceId(systemConfigService.getValue(SYSTEM_STORAGE_SPACE_ID_KEY, null));
        return R.ok(vo);
    }

    /**
     * 更新系统数据目录配置。
     */
    @PutMapping("/system-config")
    public R<Void> updateSystemConfig(@Valid @RequestBody SystemStorageConfigUpdateDto dto) {
        storageSpaceService.getById(dto.getSystemSpaceId());
        systemConfigService.setValue(SYSTEM_STORAGE_SPACE_ID_KEY, dto.getSystemSpaceId());
        return R.ok();
    }
}
