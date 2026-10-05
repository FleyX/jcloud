package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.service.MailService;
import com.fleyx.jcloud.service.NotificationLogService;
import com.fleyx.jcloud.service.support.NotificationSwitchSupport;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知管理端控制器单元测试：事件开关列表不含仅站内渠道的兜底事件，且该事件不可配置。
 */
class AdminNotificationControllerTest {

    private final SmtpConfigSupport smtpConfigSupport = mock(SmtpConfigSupport.class);

    private final MailService mailService = mock(MailService.class);

    private final NotificationSwitchSupport notificationSwitchSupport = mock(NotificationSwitchSupport.class);

    private final NotificationLogService notificationLogService = mock(NotificationLogService.class);

    private final AdminNotificationController controller = new AdminNotificationController(
            smtpConfigSupport, mailService, notificationSwitchSupport, notificationLogService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * 事件开关列表：返回七类可配置事件，最后一个为存储空间容量告警，不含 email_share_failed。
     */
    @Test
    void shouldListOnlyConfigurableEventSwitches() throws Exception {
        when(notificationSwitchSupport.isEnabled(any())).thenReturn(true);

        mockMvc.perform(get("/jcloud/api/admin/notification/event-switches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[0].eventType").value("transfer_completed"))
                .andExpect(jsonPath("$.data[6].eventType").value("storage_capacity_alert"));

        verify(notificationSwitchSupport, never()).set(any(), anyBoolean());
    }

    /**
     * email_share_failed 不可配置：PUT 被拒（PARAM_ERROR），不会写入开关。
     */
    @Test
    void shouldRejectSwitchUpdateForInAppOnlyEvent() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/notification/event-switches/email_share_failed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg", containsString("未知的通知事件类型")));

        verify(notificationSwitchSupport, never()).set(any(), anyBoolean());
    }

    /**
     * 可配置事件：PUT 透传事件类型与启用状态到开关支撑类。
     */
    @Test
    void shouldUpdateSwitchForConfigurableEvent() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/notification/event-switches/transfer_completed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationSwitchSupport).set(NotificationEventType.TRANSFER_COMPLETED, false);
    }
}
