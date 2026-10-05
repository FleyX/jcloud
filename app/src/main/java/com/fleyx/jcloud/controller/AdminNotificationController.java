package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.bo.SmtpConfig;
import com.fleyx.jcloud.model.dto.NotificationEventSwitchDto;
import com.fleyx.jcloud.model.dto.NotificationLogPageQueryDto;
import com.fleyx.jcloud.model.dto.SmtpConfigDto;
import com.fleyx.jcloud.model.dto.SmtpTestMailDto;
import com.fleyx.jcloud.model.vo.NotificationEventSwitchVo;
import com.fleyx.jcloud.model.vo.NotificationLogVo;
import com.fleyx.jcloud.model.vo.SmtpConfigVo;
import com.fleyx.jcloud.service.MailService;
import com.fleyx.jcloud.service.NotificationLogService;
import com.fleyx.jcloud.service.support.NotificationSwitchSupport;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 通知管理端控制器（管理员）：发件邮箱（SMTP）配置与测试发送、通知事件开关、发送记录查询。
 */
@RestController
@RequestMapping(CommonConstant.API + "/admin/notification")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final SmtpConfigSupport smtpConfigSupport;
    private final MailService mailService;
    private final NotificationSwitchSupport notificationSwitchSupport;
    private final NotificationLogService notificationLogService;

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
        vo.setAttachmentMaxSizeMb(smtpConfigSupport.attachmentMaxSizeMb());
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

    /**
     * 查询全部通知事件的启用状态（不含仅走站内渠道的兜底事件）。
     */
    @GetMapping("/event-switches")
    public R<List<NotificationEventSwitchVo>> listEventSwitches() {
        return R.ok(Arrays.stream(NotificationEventType.values())
                .filter(eventType -> !eventType.isInAppOnly())
                .map(eventType -> {
                    NotificationEventSwitchVo vo = new NotificationEventSwitchVo();
                    vo.setEventType(eventType.getValue());
                    vo.setName(eventType.getDisplayName());
                    vo.setEnabled(notificationSwitchSupport.isEnabled(eventType));
                    return vo;
                })
                .toList());
    }

    /**
     * 设置单个通知事件的启用状态，禁用后该事件不触发任何渠道；仅站内渠道的兜底事件不可配置。
     */
    @PutMapping("/event-switches/{eventType}")
    public R<Void> updateEventSwitch(@PathVariable String eventType,
                                     @Valid @RequestBody NotificationEventSwitchDto dto) {
        NotificationEventType type = NotificationEventType.fromValue(eventType)
                .filter(candidate -> !candidate.isInAppOnly())
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR, "未知的通知事件类型：" + eventType));
        notificationSwitchSupport.set(type, dto.getEnabled());
        return R.ok();
    }

    /**
     * 分页查询邮件发送记录（按发送时间倒序）。
     */
    @GetMapping("/send-logs")
    public R<IPage<NotificationLogVo>> pageSendLogs(NotificationLogPageQueryDto dto) {
        return R.ok(notificationLogService.page(dto));
    }
}
