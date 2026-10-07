package com.fleyx.jcloud.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 逐收件人「发送并记录」支撑类单元测试：单收件人失败不影响其余收件人，每收件人各一条记录。
 */
class MailSendSupportTest {

    private final NotificationLogSupport notificationLogSupport = mock(NotificationLogSupport.class);

    private final MailSendSupport mailSendSupport = new MailSendSupport(notificationLogSupport);

    /**
     * 两个收件人：第一个失败、第二个成功，两次发送均执行且各写一条记录。
     */
    @Test
    void shouldIsolatePerRecipientFailureAndRecordEach() {
        AtomicInteger attempts = new AtomicInteger();
        List<MailSendSupport.SendOutcome> outcomes = mailSendSupport.sendEachAndRecord(
                "email_share", List.of("bad@example.com", "good@example.com"), "主题",
                recipient -> {
                    attempts.incrementAndGet();
                    if ("bad@example.com".equals(recipient)) {
                        throw new IllegalStateException("发送被拒");
                    }
                });

        assertEquals(2, attempts.get(), "单个收件人失败不应中断后续发送");
        assertEquals(2, outcomes.size());
        assertFalse(outcomes.get(0).success());
        assertEquals("发送被拒", outcomes.get(0).error());
        assertTrue(outcomes.get(1).success());
        verify(notificationLogSupport).record("email_share", "bad@example.com", "主题", false, "发送被拒");
        verify(notificationLogSupport).record("email_share", "good@example.com", "主题", true, null);
        verifyNoMoreInteractions(notificationLogSupport);
    }
}
