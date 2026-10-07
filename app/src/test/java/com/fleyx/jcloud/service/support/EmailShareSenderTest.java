package com.fleyx.jcloud.service.support;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 邮件分享异步发送器测试：远程文件节点经协议适配器下载并组装为附件；
 * 附件读取失败时逐收件人写失败记录并给操作者补站内通知，不再尝试发信。
 */
class EmailShareSenderTest {

    private final MailService mailService = mock(MailService.class);
    private final NotificationLogSupport notificationLogSupport = mock(NotificationLogSupport.class);
    private final MailSendSupport mailSendSupport = new MailSendSupport(notificationLogSupport);
    private final EmailShareRecipientSupport recipientSupport = mock(EmailShareRecipientSupport.class);
    private final MediaFileStreamSupport mediaFileStreamSupport = mock(MediaFileStreamSupport.class);
    private final RemoteFileService remoteFileService = mock(RemoteFileService.class);
    private final NotificationEventSupport notificationEventSupport = mock(NotificationEventSupport.class);

    private final EmailShareSender sender = new EmailShareSender(mailService, notificationLogSupport,
            mailSendSupport, recipientSupport, mediaFileStreamSupport, remoteFileService, notificationEventSupport);

    private FileNode remoteNode(String name) {
        FileNode node = new FileNode();
        node.setId("node-1");
        node.setUserId("user-1");
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        node.setSize(12L);
        return node;
    }

    /**
     * 远程节点：经协议适配器下载字节并作为附件发送，写成功记录并更新收件人记忆。
     */
    @Test
    void shouldReadRemoteNodeAndSendAttachment() {
        FileNode node = remoteNode("kindle.mobi");
        byte[] content = "remote-bytes".getBytes();
        when(remoteFileService.download(node, "user-1")).thenReturn(new FileDownloadResult(
                node.getName(), new ByteArrayInputStream(content), "application/octet-stream", (long) content.length));

        sender.sendAttachmentEmails("user-1", "主题", "<p>正文</p>", List.of("to@example.com"), List.of(node));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MailAttachment>> captor = ArgumentCaptor.forClass(List.class);
        verify(mailService).sendWithAttachments(eq("to@example.com"), eq("主题"), eq("<p>正文</p>"), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("kindle.mobi", captor.getValue().get(0).fileName());
        assertArrayEquals(content, captor.getValue().get(0).content());
        verify(notificationLogSupport).record(EmailShareSender.EVENT_TYPE, "to@example.com", "主题", true, null);
        verify(recipientSupport).record("user-1", List.of("to@example.com"));
        // 远程路径不触碰本地物理路径解析
        verifyNoInteractions(mediaFileStreamSupport, notificationEventSupport);
    }

    /**
     * 附件读取失败：逐收件人写失败记录，给操作者补一条 email_share_failed 通知，不尝试发信。
     */
    @Test
    void shouldNotifyOperatorWhenAttachmentReadFails() {
        FileNode node = remoteNode("kindle.mobi");
        when(remoteFileService.download(node, "user-1")).thenThrow(
                new SystemException(ResultCode.SYSTEM_ERROR, "读取远程文件失败：kindle.mobi"));

        sender.sendAttachmentEmails("user-1", "主题", "<p>正文</p>", List.of("to@example.com"), List.of(node));

        verify(mailService, never()).sendWithAttachments(anyString(), anyString(), anyString(), any());
        verify(notificationLogSupport).record(eq(EmailShareSender.EVENT_TYPE), eq("to@example.com"),
                eq("主题"), eq(false), anyString());
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationEventSupport).publishAfterCommit(captor.capture());
        assertEquals(NotificationEventType.EMAIL_SHARE_FAILED, captor.getValue().getEventType());
        assertEquals("user-1", captor.getValue().getTargetUserId());
    }
}
