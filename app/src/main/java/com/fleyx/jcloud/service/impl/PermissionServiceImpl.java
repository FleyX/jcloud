package com.fleyx.jcloud.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.PermissionMapper;
import com.fleyx.jcloud.mapper.PermissionResourceMapper;
import com.fleyx.jcloud.mapper.ResourceMapper;
import com.fleyx.jcloud.mapper.RolePermissionMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.convert.PermissionConvert;
import com.fleyx.jcloud.model.dto.PermissionSaveDto;
import com.fleyx.jcloud.model.dto.PermissionUpdateDto;
import com.fleyx.jcloud.model.po.Permission;
import com.fleyx.jcloud.model.po.PermissionResource;
import com.fleyx.jcloud.model.po.Resource;
import com.fleyx.jcloud.model.po.RolePermission;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.PermissionVo;
import com.fleyx.jcloud.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final PermissionMapper permissionMapper;
    private final PermissionConvert permissionConvert;
    private final PermissionResourceMapper permissionResourceMapper;
    private final ResourceMapper resourceMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserPermissionCache userPermissionCache;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PermissionVo savePermission(PermissionSaveDto dto) {
        checkCodeUnique(dto.getCode(), null);
        validateStatus(dto.getStatus());
        validateParentId(null, dto.getParentId());
        validateResourceIds(dto.getResourceIds());
        Permission permission = permissionConvert.saveDtoToPo(dto);
        if (permission.getStatus() == null) {
            permission.setStatus(CommonStatus.ENABLED.getCode());
        }
        permissionMapper.insert(permission);
        savePermissionResources(permission.getId(), dto.getResourceIds());
        return enrichPermissionVo(permission);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PermissionVo updatePermission(Long id, PermissionUpdateDto dto) {
        Permission permission = requirePermission(id);
        validateStatus(dto.getStatus());
        validateParentId(id, dto.getParentId());
        validateResourceIds(dto.getResourceIds());
        permissionConvert.updatePoFromDto(dto, permission);
        permissionMapper.updateById(permission);
        savePermissionResources(id, dto.getResourceIds());
        evictUserCachesByPermissionId(id);
        Permission updated = permissionMapper.selectById(id);
        return enrichPermissionVo(updated);
    }

    @Override
    public PermissionVo getById(Long id) {
        Permission permission = requirePermission(id);
        return enrichPermissionVo(permission);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeById(Long id) {
        Permission permission = requirePermission(id);
        Set<Long> descendants = permissionMapper.selectDescendantIds(id);
        if (descendants.size() > 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "请先删除子权限");
        }
        Long roleCount = rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, id)
        );
        if (roleCount != null && roleCount > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "该权限已分配给角色，无法删除");
        }
        permissionMapper.deleteById(id);
        permissionResourceMapper.deleteByPermissionId(id);
        evictUserCachesByPermissionId(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        requirePermission(id);
        validateStatus(status);
        Permission update = new Permission();
        update.setId(id);
        update.setStatus(status);
        permissionMapper.updateById(update);
        evictUserCachesByPermissionId(id);
    }

    @Override
    public List<PermissionTreeVo> tree() {
        List<Permission> all = permissionMapper.selectAllValid();
        List<PermissionTreeVo> vos = permissionConvert.poListToTreeVoList(all);
        if (CollUtil.isEmpty(vos)) {
            return List.of();
        }
        Map<Long, PermissionTreeVo> map = vos.stream()
                .collect(Collectors.toMap(PermissionTreeVo::getId, v -> v));
        List<PermissionTreeVo> roots = new ArrayList<>();
        for (PermissionTreeVo vo : vos) {
            if (vo.getParentId() == null) {
                roots.add(vo);
            } else {
                PermissionTreeVo parent = map.get(vo.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(vo);
                } else {
                    roots.add(vo);
                }
            }
        }
        return roots;
    }

    @Override
    public List<Resource> listResources() {
        return resourceMapper.selectList(
                new LambdaQueryWrapper<Resource>()
                        .eq(Resource::getStatus, CommonStatus.ENABLED.getCode())
                        .orderByAsc(Resource::getType)
                        .orderByAsc(Resource::getCode)
        );
    }

    private Permission requirePermission(Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "权限不存在");
        }
        return permission;
    }

    private void checkCodeUnique(String code, Long excludeId) {
        if (StrUtil.isBlank(code)) {
            return;
        }
        LambdaQueryWrapper<Permission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Permission::getCode, code);
        if (excludeId != null) {
            wrapper.ne(Permission::getId, excludeId);
        }
        if (permissionMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "权限编码已存在");
        }
    }

    private void validateStatus(Integer status) {
        if (status == null) {
            return;
        }
        if (!status.equals(CommonStatus.ENABLED.getCode()) && !status.equals(CommonStatus.DISABLED.getCode())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 1（启用）或 0（禁用）");
        }
    }

    private void validateParentId(Long currentId, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (currentId != null && currentId.equals(parentId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能将权限设为其自身子级");
        }
        Permission parent = permissionMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "父级权限不存在");
        }
        if (!parent.getStatus().equals(CommonStatus.ENABLED.getCode())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "父级权限已禁用");
        }
        if (currentId != null) {
            Set<Long> descendants = permissionMapper.selectDescendantIds(currentId);
            if (descendants.contains(parentId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "不能将权限设为其自身或其后代的子级");
            }
        }
    }

    private void validateResourceIds(List<Long> resourceIds) {
        if (CollUtil.isEmpty(resourceIds)) {
            return;
        }
        List<Long> distinctIds = resourceIds.stream().distinct().toList();
        long validCount = resourceMapper.selectCount(
                new LambdaQueryWrapper<Resource>().in(Resource::getId, distinctIds)
        );
        if (validCount != distinctIds.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "存在无效的资源 ID");
        }
    }

    private void savePermissionResources(Long permissionId, List<Long> resourceIds) {
        permissionResourceMapper.deleteByPermissionId(permissionId);
        if (CollUtil.isEmpty(resourceIds)) {
            return;
        }
        List<PermissionResource> relations = resourceIds.stream()
                .distinct()
                .map(rid -> {
                    PermissionResource pr = new PermissionResource();
                    pr.setPermissionId(permissionId);
                    pr.setResourceId(rid);
                    pr.setCreateTime(LocalDateTime.now());
                    return pr;
                })
                .toList();
        permissionResourceMapper.batchInsert(relations);
    }

    private PermissionVo enrichPermissionVo(Permission permission) {
        if (permission == null) {
            return null;
        }
        PermissionVo vo = permissionConvert.poToVo(permission);
        vo.setResourceIds(permissionResourceMapper.selectResourceIdsByPermissionId(permission.getId()));
        return vo;
    }

    private void evictUserCachesByPermissionId(Long permissionId) {
        List<Long> roleIds = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, permissionId)
        ).stream().map(RolePermission::getRoleId).distinct().toList();

        for (Long roleId : roleIds) {
            List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
            if (CollUtil.isNotEmpty(userIds)) {
                userIds.forEach(userPermissionCache::evict);
            }
        }
    }
}
