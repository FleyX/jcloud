package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.MailAttachment;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.service.MailService;
import com.fleyx.jcloud.service.RemoteFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 邮件分享异步发送器：逐收件人发信、写发送记录，并把成功地址并入收件人记忆。
 * <p>
 * 异步执行不阻塞接口返回；单个收件人失败不影响其余收件人，仅记录失败原因。
 * 附件直发模式读取文件字节并组装附件，任一环节失败时给操作者补一条站内汇总通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailShareSender {

    /**
     * 发送记录中的事件类型标识（非通知事件枚举，展示时回退为该值）。
     */
    public static final String EVENT_TYPE = "email_share";

    /**
     * 站内通知内容中失败原因的最大长度。
     */
    private static final int REASON_MAX_LENGTH = 500;

    private final MailService mailService;
    private final NotificationLogSupport notificationLogSupport;
    private final EmailShareRecipientSupport recipientSupport;
    private final MediaFileStreamSupport mediaFileStreamSupport;
    private final RemoteFileService remoteFileService;
    private final NotificationEventSupport notificationEventSupport;

    /**
     * 异步逐收件人发送分享邮件。
     *
     * @param userId     操作者用户 ID，用于收件人记忆
     * @param subject    邮件主题
     * @param html       HTML 正文
     * @param recipients 收件邮箱列表
     */
    @Async
    public void sendLinkEmails(String userId, String subject, String html, List<String> recipients) {
        List<String> succeeded = new ArrayList<>();
        for (String recipient : recipients) {
            try {
                mailService.send(recipient, subject, html);
                notificationLogSupport.record(EVENT_TYPE, recipient, subject, true, null);
                succeeded.add(recipient);
            } catch (Exception e) {
                log.warn("邮件分享发送失败：to={}, subject={}", recipient, subject, e);
                notificationLogSupport.record(EVENT_TYPE, recipient, subject, false, e.getMessage());
            }
        }
        recipientSupport.record(userId, succeeded);
    }

    /**
     * 异步读取文件字节、组装附件并逐收件人发送。
     * <p>
     * 读取失败或任一收件人发送失败时，除逐收件人写失败记录外，还给操作者补一条站内汇总通知。
     *
     * @param userId     操作者用户 ID，用于收件人记忆与失败通知
     * @param subject    邮件主题
     * @param html       HTML 正文
     * @param recipients 收件邮箱列表
     * @param nodes      附件文件节点列表
     */
    @Async
    public void sendAttachmentEmails(String userId, String subject, String html,
                                     List<String> recipients, List<FileNode> nodes) {
        List<MailAttachment> attachments;
        try {
            attachments = loadAttachments(nodes, userId);
        } catch (Exception e) {
            log.warn("邮件分享附件读取失败：userId={}, files={}", userId, names(nodes), e);
            recordFailures(recipients, subject, e.getMessage());
            notifyFailure(userId, nodes, e.getMessage());
            return;
        }

        List<String> succeeded = new ArrayList<>();
        List<String> failedRecipients = new ArrayList<>();
        String lastError = null;
        for (String recipient : recipients) {
            try {
                mailService.sendWithAttachments(recipient, subject, html, attachments);
                notificationLogSupport.record(EVENT_TYPE, recipient, subject, true, null);
                succeeded.add(recipient);
            } catch (Exception e) {
                log.warn("邮件分享发送失败：to={}, subject={}", recipient, subject, e);
                notificationLogSupport.record(EVENT_TYPE, recipient, subject, false, e.getMessage());
                failedRecipients.add(recipient);
                lastError = e.getMessage();
            }
        }
        recipientSupport.record(userId, succeeded);
        if (!failedRecipients.isEmpty()) {
            notifyFailure(userId, nodes,
                    "收件人 " + String.join("、", failedRecipients) + " 发送失败：" + lastError);
        }
    }

    private List<MailAttachment> loadAttachments(List<FileNode> nodes, String userId) {
        List<MailAttachment> attachments = new ArrayList<>(nodes.size());
        for (FileNode node : nodes) {
            attachments.add(new MailAttachment(node.getName(), readBytes(node, userId)));
        }
        return attachments;
    }

    /**
     * 读取文件字节：远程节点经协议适配器下载，本地节点推导物理路径直接读。
     */
    private byte[] readBytes(FileNode node, String userId) {
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            FileDownloadResult result = remoteFileService.download(node, userId);
            try (InputStream in = result.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new SystemException(ResultCode.SYSTEM_ERROR, "读取远程文件失败：" + node.getName(), e);
            }
        }
        try {
            return Files.readAllBytes(mediaFileStreamSupport.resolveLocalPath(node, userId));
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "读取本地文件失败：" + node.getName(), e);
        }
    }

    private void recordFailures(List<String> recipients, String subject, String error) {
        for (String recipient : recipients) {
            notificationLogSupport.record(EVENT_TYPE, recipient, subject, false, error);
        }
    }

    /**
     * 给操作者补一条站内汇总通知（eventType=email_share_failed），含文件名与失败原因。
     */
    private void notifyFailure(String userId, List<FileNode> nodes, String reason) {
        String content = "文件：" + names(nodes) + "\n失败原因："
                + StrUtil.subPre(StrUtil.blankToDefault(reason, "未知原因"), REASON_MAX_LENGTH);
        notificationEventSupport.publishAfterCommit(new NotificationEvent(this,
                NotificationEventType.EMAIL_SHARE_FAILED, userId, "邮件分享失败", content));
    }

    private String names(List<FileNode> nodes) {
        return nodes.stream().map(FileNode::getName).collect(Collectors.joining("、"));
    }
}
