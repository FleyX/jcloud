package com.fleyx.jcloud.service.impl;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.ShareMapper;
import com.fleyx.jcloud.model.dto.EmailShareLinkDto;
import com.fleyx.jcloud.model.po.Share;
import com.fleyx.jcloud.service.EmailShareService;
import com.fleyx.jcloud.service.support.EmailShareRecipientSupport;
import com.fleyx.jcloud.service.support.EmailShareSender;
import com.fleyx.jcloud.service.support.MailNotificationChannel;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 邮件分享业务实现（链接分享模式）。
 * <p>
 * 分享本身由既有分享创建流程完成，本实现只负责校验、组装邮件正文并异步发送。
 */
@Service
@RequiredArgsConstructor
public class EmailShareServiceImpl implements EmailShareService {

    /**
     * 收件人数量上限。
     */
    public static final int MAX_RECIPIENTS = 20;

    private static final String LINK_TEXT = "查看分享";

    private final ShareMapper shareMapper;
    private final SmtpConfigSupport smtpConfigSupport;
    private final EmailShareRecipientSupport recipientSupport;
    private final EmailShareSender emailShareSender;
    private final MailNotificationChannel mailNotificationChannel;

    @Override
    public void sendLink(String userId, String username, EmailShareLinkDto dto) {
        List<String> recipients = normalizeRecipients(dto.getRecipients());
        requireOwnShare(dto.getShareCode(), userId);
        if (!smtpConfigSupport.isConfigured()) {
            throw new BusinessException("未配置发件邮箱，通知功能不可用");
        }
        String subject = username + " 与你分享了文件";
        String html = mailNotificationChannel.buildHtml(subject, buildContent(dto), dto.getShareUrl(), LINK_TEXT);
        emailShareSender.sendLinkEmails(userId, subject, html, recipients);
    }

    @Override
    public List<String> recentRecipients(String userId) {
        return recipientSupport.list(userId);
    }

    private Share requireOwnShare(String shareCode, String userId) {
        Share share = shareMapper.selectByShareCode(shareCode);
        if (share == null || !userId.equals(share.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该分享");
        }
        return share;
    }

    private String buildContent(EmailShareLinkDto dto) {
        StringBuilder content = new StringBuilder("分享名称：").append(dto.getShareName())
                .append("\n点击下方按钮即可查看分享内容。");
        if (StrUtil.isNotBlank(dto.getPassword())) {
            content.append("\n访问密码：").append(dto.getPassword());
        }
        return content.toString();
    }

    /**
     * 收件人去空、去重、逐个校验邮箱格式，并限制数量上限。
     */
    private List<String> normalizeRecipients(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "收件邮箱不能为空");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String recipient : raw) {
            String email = StrUtil.trim(recipient);
            if (StrUtil.isBlank(email)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "收件邮箱不能为空");
            }
            if (!Validator.isEmail(email)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "收件邮箱格式不正确：" + email);
            }
            normalized.add(email);
        }
        if (normalized.size() > MAX_RECIPIENTS) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "收件人最多 " + MAX_RECIPIENTS + " 个");
        }
        return new ArrayList<>(normalized);
    }
}
