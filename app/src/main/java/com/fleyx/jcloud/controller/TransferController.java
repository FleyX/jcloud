package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.vo.TransferTaskVo;
import com.fleyx.jcloud.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跨来源传输控制器：本地与远程挂载之间的复制/移动任务。
 */
@RestController
@RequestMapping("/jcloud/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    /**
     * 创建跨来源移动任务。
     *
     * @param dto 操作参数
     * @return 传输任务
     */
    @PostMapping("/move")
    public R<TransferTaskVo> move(@RequestBody FileExecuteOperationDto dto) {
        return R.ok(transferService.createTransfer(dto, "move", UserContext.get().id()));
    }

    /**
     * 创建跨来源复制任务。
     *
     * @param dto 操作参数
     * @return 传输任务
     */
    @PostMapping("/copy")
    public R<TransferTaskVo> copy(@RequestBody FileExecuteOperationDto dto) {
        return R.ok(transferService.createTransfer(dto, "copy", UserContext.get().id()));
    }

    /**
     * 查询最近传输任务（含进行中）。
     *
     * @return 传输任务列表
     */
    @GetMapping("/recent")
    public R<List<TransferTaskVo>> recent() {
        return R.ok(transferService.listRecent(UserContext.get().id(), 10));
    }

    /**
     * 查询传输任务详情。
     *
     * @param id 任务 ID
     * @return 传输任务
     */
    @GetMapping("/{id}")
    public R<TransferTaskVo> detail(@PathVariable String id) {
        return R.ok(transferService.getTask(id, UserContext.get().id()));
    }

    /**
     * 取消传输任务。
     *
     * @param id 任务 ID
     * @return 空结果
     */
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable String id) {
        transferService.cancel(id, UserContext.get().id());
        return R.ok();
    }
}
