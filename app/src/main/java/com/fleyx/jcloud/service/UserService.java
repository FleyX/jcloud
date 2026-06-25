package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.ChangePasswordDto;
import com.fleyx.jcloud.model.dto.UserPageQueryDto;
import com.fleyx.jcloud.model.dto.UserProfileUpdateDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.dto.UserUpdateRolesDto;
import com.fleyx.jcloud.model.vo.UserProfileVo;
import com.fleyx.jcloud.model.vo.UserVo;

import java.util.List;

/**
 * 用户业务接口。
 */
public interface UserService {

    /**
     * 新增用户。
     *
     * @param dto 用户保存 DTO
     * @return 用户视图
     */
    UserVo saveUser(UserSaveDto dto);

    /**
     * 根据 ID 查询用户。
     *
     * @param id 用户 ID
     * @return 用户视图
     */
    UserVo getById(Long id);

    /**
     * 根据用户名关键字查询用户。
     *
     * @param username 用户名关键字
     * @return 用户视图列表
     */
    List<UserVo> listByUsername(String username);

    /**
     * 根据 ID 删除用户（逻辑删除）。
     *
     * @param id 用户 ID
     * @return 是否成功
     */
    boolean removeById(Long id);

    /**
     * 分页查询用户。
     *
     * @param dto 分页查询条件
     * @return 分页结果
     */
    IPage<UserVo> pageUsers(UserPageQueryDto dto);

    /**
     * 修改用户角色。
     *
     * @param dto 用户角色更新 DTO
     */
    void updateRoles(UserUpdateRolesDto dto);

    /**
     * 修改用户启用/禁用状态。
     *
     * @param dto 用户状态 DTO
     */
    void updateStatus(UserStatusDto dto);

    /**
     * 更新用户信息（昵称、邮箱、角色、状态、密码）。
     *
     * @param dto 用户更新 DTO
     * @return 更新后的用户视图
     */
    UserVo updateUser(UserUpdateDto dto);

    /**
     * 批量删除用户（自动跳过超级管理员）。
     *
     * @param userIds 用户 ID 列表
     * @return 实际删除的用户 ID 列表
     */
    List<Long> batchDelete(List<Long> userIds);

    /**
     * 批量修改用户状态（自动跳过超级管理员）。
     *
     * @param dto 批量状态 DTO
     * @return 实际更新状态的用户 ID 列表
     */
    List<Long> batchUpdateStatus(BatchUserStatusDto dto);

    /**
     * 获取当前登录用户个人信息。
     *
     * @param userId 当前用户 ID
     * @return 个人信息视图
     */
    UserProfileVo getUserProfile(Long userId);

    /**
     * 更新当前登录用户个人信息。
     *
     * @param userId 当前用户 ID
     * @param dto    更新内容
     * @return 更新后的个人信息视图
     */
    UserProfileVo updateUserProfile(Long userId, UserProfileUpdateDto dto);

    /**
     * 修改当前登录用户密码。
     *
     * @param userId 当前用户 ID
     * @param dto    密码修改 DTO
     */
    void changePassword(Long userId, ChangePasswordDto dto);

    /**
     * 为用户绑定默认存储空间与配额。
     *
     * @param dto 用户存储空间绑定 DTO
     */
    void bindStorageSpace(UserStorageDto dto);
}
