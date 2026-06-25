package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.model.dto.UserMigrationSubmitDto;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;
import com.fleyx.jcloud.service.UserMigrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户存储空间迁移管理控制器（管理员）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/admin/users")
@RequiredArgsConstructor
public class AdminUserMigrationController {

    private final UserMigrationService userMigrationService;

    /**
     * 提交用户存储空间迁移任务。
     */
    @PostMapping("/{id}/migrate")
    public R<UserMigrationTaskVo> submitMigration(@PathVariable Long id,
                                                  @Valid @RequestBody UserMigrationSubmitDto dto) {
        dto.setUserId(id);
        return R.ok(userMigrationService.submitMigration(dto));
    }

    /**
     * 查询用户最新迁移任务。
     */
    @GetMapping("/{id}/migration-task")
    public R<UserMigrationTaskVo> getLatestTask(@PathVariable Long id) {
        return R.ok(userMigrationService.getLatestTaskByUserId(id));
    }
}
