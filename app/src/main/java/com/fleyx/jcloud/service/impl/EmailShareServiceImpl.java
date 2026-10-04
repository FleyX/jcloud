package com.fleyx.jcloud.service.impl;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.ShareMapper;
import com.fleyx.jcloud.model.dto.EmailShareAttachmentDto;
import com.fleyx.jcloud.model.dto.EmailShareLinkDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.Share;
import com.fleyx.jcloud.service.EmailShareService;
import com.fleyx.jcloud.service.support.EmailShareRecipientSupport;
import com.fleyx.jcloud.service.support.EmailShareSender;
import com.fleyx.jcloud.service.support.FileNodeSupport;
import com.fleyx.jcloud.service.support.MailNotificationChannel;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 邮件分享业务实现（链接分享模式与附件直发模式）。
 * <p>
 * 链接分享本身由既有分享创建流程完成，本实现只负责校验、组装邮件正文并异步发送；
 * 附件直发校验文件归属与大小上限后交异步发送器组装附件。
 */
@Service
@RequiredArgsConstructor
public class EmailShareServiceImpl implements EmailShareService {

    /**
     * 收件人数量上限。
     */
    public static final int MAX_RECIPIENTS = 20;

    private static final String LINK_TEXT = "查看分享";

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final ShareMapper shareMapper;
    private final SmtpConfigSupport smtpConfigSupport;
    private final EmailShareRecipientSupport recipientSupport;
    private final EmailShareSender emailShareSender;
    private final MailNotificationChannel mailNotificationChannel;
    private final FileNodeSupport fileNodeSupport;

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
    public void sendAttachment(String userId, String username, EmailShareAttachmentDto dto) {
        List<String> recipients = normalizeRecipients(dto.getRecipients());
        List<FileNode> nodes = requireAttachmentNodes(userId, dto.getFileNodeIds());
        requireWithinSizeLimit(nodes);
        if (!smtpConfigSupport.isConfigured()) {
            throw new BusinessException("未配置发件邮箱，通知功能不可用");
        }
        String subject = username + " 与你分享了文件";
        String html = mailNotificationChannel.buildHtml(subject, buildAttachmentContent(nodes), null, null);
        emailShareSender.sendAttachmentEmails(userId, subject, html, recipients, nodes);
    }

    @Override
    public List<String> recentRecipients(String userId) {
        return recipientSupport.list(userId);
    }

    /**
     * 逐个校验文件节点归属与类型，文件夹或他人节点均拒绝。
     */
    private List<FileNode> requireAttachmentNodes(String userId, List<String> fileNodeIds) {
        List<FileNode> nodes = new ArrayList<>();
        for (String fileNodeId : fileNodeIds) {
            FileNode node = fileNodeSupport.getOwnedNode(fileNodeId, userId);
            if (!FileNodeConstants.TYPE_FILE.equals(node.getType())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "文件夹不支持附件直发：" + node.getName());
            }
            nodes.add(node);
        }
        return nodes;
    }

    private void requireWithinSizeLimit(List<FileNode> nodes) {
        long totalBytes = nodes.stream().mapToLong(node -> node.getSize() == null ? 0L : node.getSize()).sum();
        int maxSizeMb = smtpConfigSupport.attachmentMaxSizeMb();
        if (totalBytes > maxSizeMb * BYTES_PER_MB) {
            throw new BusinessException("超过附件大小上限 " + maxSizeMb + "MB，请改用链接分享");
        }
    }

    private String buildAttachmentContent(List<FileNode> nodes) {
        String names = nodes.stream().map(FileNode::getName).collect(Collectors.joining("、"));
        return "文件：" + names + "\n文件已作为附件随本邮件发送。";
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
