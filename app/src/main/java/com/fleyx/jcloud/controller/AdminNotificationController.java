package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.model.bo.SmtpConfig;
import com.fleyx.jcloud.model.dto.SmtpConfigDto;
import com.fleyx.jcloud.model.dto.SmtpTestMailDto;
import com.fleyx.jcloud.model.vo.SmtpConfigVo;
import com.fleyx.jcloud.service.MailService;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知管理端控制器（管理员）：发件邮箱（SMTP）配置与测试发送。
 */
@RestController
@RequestMapping(CommonConstant.API + "/admin/notification")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final SmtpConfigSupport smtpConfigSupport;
    private final MailService mailService;

    /**
     * 查询发件邮箱配置，密码不回显。
     */
    @GetMapping("/smtp-config")
    public R<SmtpConfigVo> getSmtpConfig() {
        SmtpConfig config = smtpConfigSupport.load();
        SmtpConfigVo vo = new SmtpConfigVo();
        vo.setHost(config.getHost());
        vo.setPort(config.getPort());
        vo.setUsername(config.getUsername());
        vo.setEncryption(config.getEncryption());
        vo.setFromAddress(config.getFromAddress());
        vo.setFromName(config.getFromName());
        vo.setHasPassword(config.getPassword() != null && !config.getPassword().isBlank());
        return R.ok(vo);
    }

    /**
     * 保存发件邮箱配置，密码为空表示保留原密码。
     */
    @PutMapping("/smtp-config")
    public R<Void> updateSmtpConfig(@Valid @RequestBody SmtpConfigDto dto) {
        smtpConfigSupport.save(dto);
        return R.ok();
    }

    /**
     * 发送测试邮件。
     */
    @PostMapping("/smtp-config/test")
    public R<Void> sendTestMail(@Valid @RequestBody SmtpTestMailDto dto) {
        mailService.sendTest(dto.getTo());
        return R.ok();
    }
}
