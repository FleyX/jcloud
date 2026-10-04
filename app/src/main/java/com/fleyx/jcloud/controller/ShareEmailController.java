package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.EmailShareAttachmentDto;
import com.fleyx.jcloud.model.dto.EmailShareLinkDto;
import com.fleyx.jcloud.model.vo.RecentRecipientsVo;
import com.fleyx.jcloud.service.EmailShareService;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮件分享控制器（登录用户）：链接分享与附件直发模式发送、最近收件人查询。
 */
@RestController
@RequestMapping(CommonConstant.API + "/shares/email")
@RequiredArgsConstructor
public class ShareEmailController {

    private final EmailShareService emailShareService;
    private final SmtpConfigSupport smtpConfigSupport;

    /**
     * 发送链接分享邮件，异步逐收件人发送，接口立即返回。
     *
     * @param dto 发送参数
     * @return 空结果
     */
    @PostMapping("/send-link")
    public R<Void> sendLink(@Valid @RequestBody EmailShareLinkDto dto) {
        CurrentUser user = UserContext.get();
        emailShareService.sendLink(user.id(), user.userCode(), dto);
        return R.ok();
    }

    /**
     * 发送附件直发邮件，异步组装附件逐收件人发送，接口立即返回。
     *
     * @param dto 发送参数
     * @return 空结果
     */
    @PostMapping("/send-attachment")
    public R<Void> sendAttachment(@Valid @RequestBody EmailShareAttachmentDto dto) {
        CurrentUser user = UserContext.get();
        emailShareService.sendAttachment(user.id(), user.userCode(), dto);
        return R.ok();
    }

    /**
     * 查询当前用户最近收件邮箱、发件邮箱是否可用与附件直发大小上限。
     *
     * @return 最近收件人、SMTP 配置状态与附件上限
     */
    @GetMapping("/recent-recipients")
    public R<RecentRecipientsVo> recentRecipients() {
        RecentRecipientsVo vo = new RecentRecipientsVo();
        vo.setSmtpConfigured(smtpConfigSupport.isConfigured());
        vo.setRecipients(emailShareService.recentRecipients(UserContext.get().id()));
        vo.setAttachmentMaxSizeMb(smtpConfigSupport.attachmentMaxSizeMb());
        return R.ok(vo);
    }
}
