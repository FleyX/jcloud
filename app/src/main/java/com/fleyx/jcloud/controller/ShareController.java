package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.ShareCreateDto;
import com.fleyx.jcloud.model.dto.SharePageQueryDto;
import com.fleyx.jcloud.model.dto.ShareUpdateDto;
import com.fleyx.jcloud.model.vo.ShareDetailVo;
import com.fleyx.jcloud.model.vo.ShareVo;
import com.fleyx.jcloud.service.ShareService;
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
 * 分享管理控制器。
 * 提供登录用户创建、查询、更新、删除分享的能力。
 */
@RestController
@RequestMapping(CommonConstant.API + "/shares")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    /**
     * 分页查询当前用户的分享列表。
     */
    @GetMapping
    public R<IPage<ShareVo>> page(SharePageQueryDto dto) {
        return R.ok(shareService.page(dto, UserContext.get().id()));
    }

    /**
     * 创建分享。
     */
    @PostMapping
    public R<ShareVo> create(@Valid @RequestBody ShareCreateDto dto) {
        return R.ok(shareService.create(dto, UserContext.get().id()));
    }

    /**
     * 查看分享详情。
     */
    @GetMapping("/{id}")
    public R<ShareDetailVo> detail(@PathVariable String id) {
        return R.ok(shareService.detail(id, UserContext.get().id()));
    }

    /**
     * 更新分享。
     */
    @PutMapping("/{id}")
    public R<ShareVo> update(@PathVariable String id, @Valid @RequestBody ShareUpdateDto dto) {
        return R.ok(shareService.update(id, dto, UserContext.get().id()));
    }

    /**
     * 删除分享。
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        shareService.delete(id, UserContext.get().id());
        return R.ok();
    }
}
