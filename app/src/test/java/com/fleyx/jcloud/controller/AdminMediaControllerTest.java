package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.impl.TmdbServiceImpl;
import com.fleyx.jcloud.service.support.TranscodeConfigResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体库管理端控制器单元测试（TMDB/转码全局配置读取与更新、参数归一化与异常包装）。
 * <p>
 * 该控制器不读取当前用户上下文（管理员全局配置，无 UserContext 依赖），故无需设置 CurrentUser。
 */
class AdminMediaControllerTest {

    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final TranscodeConfigResolver transcodeConfigResolver = mock(TranscodeConfigResolver.class);

    private final AdminMediaController controller =
            new AdminMediaController(systemConfigService, transcodeConfigResolver);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * GET /admin/media/tmdb-config：读取系统配置表（API Key/代理，缺省为空串），返回 R.ok 包装的 TMDB 配置。
     */
    @Test
    void shouldReturnTmdbConfigFromSystemConfig() throws Exception {
        when(systemConfigService.getValue(TmdbServiceImpl.CONFIG_KEY_API_KEY, "")).thenReturn("key-123");
        when(systemConfigService.getValue(TmdbServiceImpl.CONFIG_KEY_PROXY, "")).thenReturn("127.0.0.1:7890");

        mockMvc.perform(get("/jcloud/api/admin/media/tmdb-config"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.apiKey").value("key-123"),
                        jsonPath("$.data.proxy").value("127.0.0.1:7890"));

        verify(systemConfigService).getValue(eq(TmdbServiceImpl.CONFIG_KEY_API_KEY), eq(""));
        verify(systemConfigService).getValue(eq(TmdbServiceImpl.CONFIG_KEY_PROXY), eq(""));
    }

    /**
     * PUT /admin/media/tmdb-config：API Key 与代理原样落库，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldUpdateTmdbConfigPersistingValues() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/media/tmdb-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"key-456\",\"proxy\":\"127.0.0.1:7890\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));

        verify(systemConfigService).setValue(eq(TmdbServiceImpl.CONFIG_KEY_API_KEY), eq("key-456"));
        verify(systemConfigService).setValue(eq(TmdbServiceImpl.CONFIG_KEY_PROXY), eq("127.0.0.1:7890"));
    }

    /**
     * PUT /admin/media/tmdb-config 请求体为空对象：null 字段归一化为空串落库。
     */
    @Test
    void shouldUpdateTmdbConfigWithNullFieldsToEmpty() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/media/tmdb-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));

        verify(systemConfigService).setValue(eq(TmdbServiceImpl.CONFIG_KEY_API_KEY), eq(""));
        verify(systemConfigService).setValue(eq(TmdbServiceImpl.CONFIG_KEY_PROXY), eq(""));
    }

    /**
     * GET /admin/media/transcode-config：硬解方式/设备读系统配置表，线程数经 Resolver 解析，返回 R.ok 包装的转码配置。
     */
    @Test
    void shouldReturnTranscodeConfigWithResolvedThreads() throws Exception {
        when(systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_HWACCEL, "auto")).thenReturn("vaapi");
        when(systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_DEVICE, "")).thenReturn("/dev/dri/renderD128");
        when(transcodeConfigResolver.resolveThreads()).thenReturn(4);

        mockMvc.perform(get("/jcloud/api/admin/media/transcode-config"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.hwaccel").value("vaapi"),
                        jsonPath("$.data.device").value("/dev/dri/renderD128"),
                        jsonPath("$.data.threads").value(4));

        verify(systemConfigService).getValue(eq(TranscodeConfigResolver.CONFIG_KEY_HWACCEL), eq("auto"));
        verify(systemConfigService).getValue(eq(TranscodeConfigResolver.CONFIG_KEY_DEVICE), eq(""));
        verify(transcodeConfigResolver).resolveThreads();
    }

    /**
     * PUT /admin/media/transcode-config：hwaccel 小写化、device 去空白、负数 threads 归零后落库。
     */
    @Test
    void shouldUpdateTranscodeConfigNormalizingValues() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/media/transcode-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hwaccel\":\"VAAPI\",\"device\":\" /dev/dri/renderD128 \",\"threads\":-3}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));

        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_HWACCEL), eq("vaapi"));
        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_DEVICE), eq("/dev/dri/renderD128"));
        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_THREADS), eq("0"));
    }

    /**
     * PUT /admin/media/transcode-config 请求体为空对象：hwaccel 缺省 auto、device 缺省空串、threads 缺省 0 落库。
     */
    @Test
    void shouldUpdateTranscodeConfigWithEmptyDtoDefaults() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/media/transcode-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));

        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_HWACCEL), eq("auto"));
        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_DEVICE), eq(""));
        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_THREADS), eq("0"));
    }

    /**
     * PUT /admin/media/transcode-config 传入非法硬解方式：控制器抛 BusinessException(PARAM_ERROR)，
     * 经 GlobalExceptionHandler 包装为 R（body code=400，HTTP 状态仍 200），配置不落库。
     */
    @Test
    void shouldRejectInvalidHwaccelWithParamError() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/media/transcode-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hwaccel\":\"cuda\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                        jsonPath("$.msg").value("非法的硬解方式: cuda"),
                        jsonPath("$.data").value(nullValue()));

        verify(systemConfigService, never()).setValue(any(), any());
    }

    /**
     * PUT /admin/media/tmdb-config 缺少请求体：HttpMessageNotReadableException 经 GlobalExceptionHandler
     * 包装为 R（body code=400，msg 固定为“请求体格式错误”），配置不落库。
     */
    @Test
    void shouldReturnParamErrorWhenBodyMissing() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/media/tmdb-config")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                        jsonPath("$.msg").value("请求体格式错误"),
                        jsonPath("$.data").value(nullValue()));

        verify(systemConfigService, never()).setValue(any(), any());
    }
}
