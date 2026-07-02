package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.RemoteMountPageQueryDto;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.RemoteMountSyncConfigUpdateDto;
import com.fleyx.jcloud.model.dto.RemoteMountUpdateDto;
import com.fleyx.jcloud.model.dto.RemoteSyncTaskPageQueryDto;
import com.fleyx.jcloud.model.vo.RemoteMountDetailVo;
import com.fleyx.jcloud.model.vo.RemoteMountHealthVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.RemoteSyncTaskVo;
import com.fleyx.jcloud.service.RemoteMountService;
import com.fleyx.jcloud.service.RemoteMountSyncService;
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

import java.util.List;

/**
 * 远程挂载管理控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/remote-mounts")
@RequiredArgsConstructor
public class RemoteMountController {

    private final RemoteMountService remoteMountService;
    private final RemoteMountSyncService remoteMountSyncService;

    /**
     * 分页查询当前用户的远程挂载。
     */
    @GetMapping
    public R<IPage<RemoteMountVo>> page(RemoteMountPageQueryDto dto) {
        return R.ok(remoteMountService.page(dto, UserContext.get().id()));
    }

    /**
     * 创建远程挂载。
     */
    @PostMapping
    public R<RemoteMountVo> create(@Valid @RequestBody RemoteMountSaveDto dto) {
        return R.ok(remoteMountService.save(dto, UserContext.get().id()));
    }

    /**
     * 查看远程挂载详情。
     */
    @GetMapping("/{id}")
    public R<RemoteMountDetailVo> detail(@PathVariable String id) {
        return R.ok(remoteMountService.detail(id, UserContext.get().id()));
    }

    /**
     * 更新远程挂载。
     */
    @PutMapping("/{id}")
    public R<RemoteMountVo> update(@PathVariable String id, @Valid @RequestBody RemoteMountUpdateDto dto) {
        dto.setId(id);
        return R.ok(remoteMountService.update(dto, UserContext.get().id()));
    }

    /**
     * 删除远程挂载（取消挂载）。
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        remoteMountService.delete(id, UserContext.get().id());
        return R.ok();
    }

    /**
     * 测试远程挂载连接（基于表单数据）。
     */
    @PostMapping("/test-connection")
    public R<Void> testConnection(@Valid @RequestBody RemoteMountSaveDto dto) {
        remoteMountService.testConnection(dto);
        return R.ok();
    }

    /**
     * 立即同步远程挂载。
     */
    @PostMapping("/{id}/sync")
    public R<RemoteSyncTaskVo> sync(@PathVariable String id) {
        return R.ok(remoteMountSyncService.submitImmediate(id, UserContext.get().id()));
    }

    /**
     * 查询远程挂载最新同步任务。
     */
    @GetMapping("/{id}/sync/task")
    public R<RemoteSyncTaskVo> latestTask(@PathVariable String id) {
        return R.ok(remoteMountSyncService.getLatestTask(id, UserContext.get().id()));
    }

    /**
     * 更新远程挂载同步配置。
     */
    @PutMapping("/{id}/sync-config")
    public R<Void> updateSyncConfig(@PathVariable String id,
                                    @Valid @RequestBody RemoteMountSyncConfigUpdateDto dto) {
        dto.setRemoteMountId(id);
        remoteMountSyncService.updateConfig(id, dto, UserContext.get().id());
        return R.ok();
    }

    /**
     * 分页查询当前用户的远程同步任务历史。
     */
    @GetMapping("/sync-tasks")
    public R<IPage<RemoteSyncTaskVo>> pageTasks(RemoteSyncTaskPageQueryDto dto) {
        return R.ok(remoteMountSyncService.pageTasks(null, UserContext.get().id(), dto));
    }

    /**
     * 检查当前用户所有远程挂载的健康状态。
     */
    @PostMapping("/health-check")
    public R<List<RemoteMountHealthVo>> healthCheck() {
        return R.ok(remoteMountService.healthCheck(UserContext.get().id()));
    }
}
