package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.model.dto.SystemInitDto;
import com.fleyx.jcloud.model.vo.SystemInitStatusVo;
import com.fleyx.jcloud.service.SystemInitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统初始化控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/admin/system")
@RequiredArgsConstructor
public class SystemInitController {

    private final SystemInitService systemInitService;

    /**
     * 查询系统初始化状态。
     */
    @GetMapping("/init-status")
    public R<SystemInitStatusVo> getInitStatus() {
        return R.ok(systemInitService.getInitStatus());
    }

    /**
     * 执行系统初始化。
     */
    @PostMapping("/initialize")
    public R<Void> initialize(@Valid @RequestBody SystemInitDto dto) {
        systemInitService.initialize(dto);
        return R.ok();
    }
}
