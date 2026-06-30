package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.model.dto.UserSyncConfigUpdateDto;
import com.fleyx.jcloud.model.vo.UserSyncConfigVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.service.UserSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户存储空间同步管理控制器（管理员）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/admin/users")
@RequiredArgsConstructor
public class AdminUserSyncController {

    private final UserSyncService userSyncService;

    /**
     * 提交立即同步任务。
     */
    @PostMapping("/{id}/sync/immediate")
    public R<UserSyncTaskVo> submitImmediate(@PathVariable String id) {
        return R.ok(userSyncService.submitImmediate(id));
    }

    /**
     * 查询用户最新同步任务。
     */
    @GetMapping("/{id}/sync/task")
    public R<UserSyncTaskVo> getLatestTask(@PathVariable String id) {
        return R.ok(userSyncService.getLatestTask(id));
    }

    /**
     * 查询用户同步配置。
     */
    @GetMapping("/{id}/sync/config")
    public R<UserSyncConfigVo> getConfig(@PathVariable String id) {
        return R.ok(userSyncService.getConfig(id));
    }

    /**
     * 更新用户同步配置。
     */
    @PutMapping("/{id}/sync/config")
    public R<UserSyncConfigVo> updateConfig(@PathVariable String id,
                                            @Valid @RequestBody UserSyncConfigUpdateDto dto) {
        dto.setUserId(id);
        return R.ok(userSyncService.updateConfig(dto));
    }
}
