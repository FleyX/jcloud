package com.fleyx.jcloud.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.PermissionMapper;
import com.fleyx.jcloud.mapper.RolePermissionMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.convert.RoleConvert;
import com.fleyx.jcloud.model.dto.RolePageQueryDto;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.po.Permission;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.RolePermission;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private static final Set<String> PROTECTED_ROLE_CODES = Set.of("super_admin", "system_admin");

    private final RoleMapper roleMapper;
    private final RoleConvert roleConvert;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;
    private final UserPermissionCache userPermissionCache;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVo saveRole(RoleSaveDto dto) {
        checkCodeUnique(dto.getCode(), null);
        validateStatus(dto.getStatus());
        validatePermissionIds(dto.getPermissionIds());
        Role role = roleConvert.saveDtoToPo(dto);
        if (role.getStatus() == null) {
            role.setStatus(CommonStatus.ENABLED.getCode());
        }
        roleMapper.insert(role);
        saveRolePermissions(role.getId(), dto.getPermissionIds());
        return enrichRoleVo(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVo updateRole(String id, RoleUpdateDto dto) {
        Role role = requireRole(id);
        validateStatus(dto.getStatus());
        validatePermissionIds(dto.getPermissionIds());
        roleConvert.updatePoFromDto(dto, role);
        roleMapper.updateById(role);

        saveRolePermissions(id, dto.getPermissionIds());
        evictUserCachesByRoleId(id);

        Role updated = roleMapper.selectById(id);
        return enrichRoleVo(updated);
    }

    @Override
    public RoleVo getById(String id) {
        Role role = requireRole(id);
        return enrichRoleVo(role);
    }

    @Override
    public List<RoleVo> listAllEnabled() {
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getStatus, CommonStatus.ENABLED.getCode())
                .orderByDesc(Role::getCreateTime);
        return roleMapper.selectList(wrapper).stream()
                .map(this::enrichRoleVo)
                .collect(Collectors.toList());
    }

    @Override
    public IPage<RoleVo> pageRoles(RolePageQueryDto dto) {
        Page<Role> pageParam = new Page<>(dto.getPageNum(), dto.getPageSize());
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Role::getCreateTime);
        if (StrUtil.isNotBlank(dto.getCode())) {
            wrapper.like(Role::getCode, dto.getCode());
        }
        if (StrUtil.isNotBlank(dto.getName())) {
            wrapper.like(Role::getName, dto.getName());
        }
        if (dto.getStatus() != null) {
            wrapper.eq(Role::getStatus, dto.getStatus());
        }
        IPage<Role> page = roleMapper.selectPage(pageParam, wrapper);
        List<RoleVo> records = page.getRecords().stream()
                .map(this::enrichRoleVo)
                .collect(Collectors.toList());
        Page<RoleVo> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeById(String id) {
        Role role = requireRole(id);
        if (PROTECTED_ROLE_CODES.contains(role.getCode())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "系统角色不允许删除");
        }
        Long userCount = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, id)
        );
        if (userCount != null && userCount > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "该角色已分配给用户，无法删除");
        }
        roleMapper.deleteById(id);
        rolePermissionMapper.deleteByRoleId(id);
        evictUserCachesByRoleId(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String id, Integer status) {
        Role role = requireRole(id);
        if (PROTECTED_ROLE_CODES.contains(role.getCode())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "系统角色不允许禁用");
        }
        validateStatus(status);
        Role update = new Role();
        update.setId(id);
        update.setStatus(status);
        roleMapper.updateById(update);
        evictUserCachesByRoleId(id);
    }

    private Role requireRole(String id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private void checkCodeUnique(String code, String excludeId) {
        if (StrUtil.isBlank(code)) {
            return;
        }
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getCode, code);
        if (excludeId != null) {
            wrapper.ne(Role::getId, excludeId);
        }
        if (roleMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "角色编码已存在");
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

    private void validatePermissionIds(List<String> permissionIds) {
        if (CollUtil.isEmpty(permissionIds)) {
            return;
        }
        List<String> distinctIds = permissionIds.stream().distinct().toList();
        long validCount = permissionMapper.selectCount(
                new LambdaQueryWrapper<Permission>().in(Permission::getId, distinctIds)
        );
        if (validCount != distinctIds.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "存在无效的权限 ID");
        }
    }

    private void saveRolePermissions(String roleId, List<String> permissionIds) {
        rolePermissionMapper.deleteByRoleId(roleId);
        if (CollUtil.isEmpty(permissionIds)) {
            return;
        }
        List<RolePermission> relations = permissionIds.stream()
                .distinct()
                .map(pid -> {
                    RolePermission rp = new RolePermission();
                    rp.setRoleId(roleId);
                    rp.setPermissionId(pid);
                    rp.setCreateTime(LocalDateTime.now());
                    return rp;
                })
                .collect(Collectors.toList());
        rolePermissionMapper.batchInsert(relations);
    }

    private RoleVo enrichRoleVo(Role role) {
        if (role == null) {
            return null;
        }
        RoleVo vo = roleConvert.poToVo(role);
        vo.setPermissionIds(rolePermissionMapper.selectPermissionIdsByRoleId(role.getId()));
        return vo;
    }

    private void evictUserCachesByRoleId(String roleId) {
        List<String> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
        if (CollUtil.isNotEmpty(userIds)) {
            userIds.forEach(userPermissionCache::evict);
        }
    }
}
