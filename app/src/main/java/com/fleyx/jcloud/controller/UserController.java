package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.ChangePasswordDto;
import com.fleyx.jcloud.model.dto.UserPageQueryDto;
import com.fleyx.jcloud.model.dto.UserProfileUpdateDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.dto.UserUpdateRolesDto;
import com.fleyx.jcloud.model.vo.UserProfileVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理控制器。
 * 接口级鉴权由 {@link com.fleyx.jcloud.filter.AuthTokenFilter} 根据用户角色绑定的 t_resource 配置统一处理。
 */
@RestController
@RequestMapping(CommonConstant.API + "/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 新增用户。
     */
    @PostMapping
    public R<UserVo> save(@Valid @RequestBody UserSaveDto dto) {
        return R.ok(userService.saveUser(dto));
    }

    /**
     * 根据 ID 查询用户。
     */
    @GetMapping("/{id}")
    public R<UserVo> getById(@PathVariable Long id) {
        return R.ok(userService.getById(id));
    }

    /**
     * 根据用户名关键字查询。
     */
    @GetMapping("/search")
    public R<List<UserVo>> listByUsername(@RequestParam String username) {
        return R.ok(userService.listByUsername(username));
    }

    /**
     * 分页查询用户。
     */
    @GetMapping
    public R<IPage<UserVo>> page(UserPageQueryDto dto) {
        return R.ok(userService.pageUsers(dto));
    }

    /**
     * 修改用户角色。
     */
    @PutMapping("/{id}/roles")
    public R<Void> updateRoles(@PathVariable Long id, @Valid @RequestBody UserUpdateRolesDto dto) {
        dto.setUserId(id);
        userService.updateRoles(dto);
        return R.ok();
    }

    /**
     * 更新用户信息。
     */
    @PutMapping("/{id}")
    public R<UserVo> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDto dto) {
        dto.setId(id);
        return R.ok(userService.updateUser(dto));
    }

    /**
     * 启用/禁用用户。
     */
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDto dto) {
        dto.setUserId(id);
        userService.updateStatus(dto);
        return R.ok();
    }

    /**
     * 删除用户。
     */
    @DeleteMapping("/{id}")
    public R<Boolean> remove(@PathVariable Long id) {
        return R.ok(userService.removeById(id));
    }

    /**
     * 批量删除用户。
     */
    @DeleteMapping("/batch")
    public R<List<Long>> batchRemove(@RequestBody List<Long> userIds) {
        return R.ok(userService.batchDelete(userIds));
    }

    /**
     * 批量启用/禁用用户。
     */
    @PutMapping("/batch/status")
    public R<List<Long>> batchUpdateStatus(@Valid @RequestBody BatchUserStatusDto dto) {
        return R.ok(userService.batchUpdateStatus(dto));
    }

    /**
     * 获取当前登录用户个人信息。
     */
    @GetMapping("/me")
    public R<UserProfileVo> getCurrentProfile() {
        return R.ok(userService.getUserProfile(UserContext.get().id()));
    }

    /**
     * 更新当前登录用户个人信息。
     */
    @PutMapping("/me")
    public R<UserProfileVo> updateCurrentProfile(@Valid @RequestBody UserProfileUpdateDto dto) {
        return R.ok(userService.updateUserProfile(UserContext.get().id(), dto));
    }

    /**
     * 修改当前登录用户密码。
     */
    @PutMapping("/me/password")
    public R<Void> changeCurrentPassword(@Valid @RequestBody ChangePasswordDto dto) {
        userService.changePassword(UserContext.get().id(), dto);
        return R.ok();
    }
}
