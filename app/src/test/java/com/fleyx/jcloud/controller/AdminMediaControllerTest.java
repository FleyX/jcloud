package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.impl.TmdbServiceImpl;
import com.fleyx.jcloud.service.support.TranscodeConfigResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
     * GET /admin/media/transcode-config：硬解方式经 Resolver 解析（无记录时反映 yml 兜底，探测落库后即落库值），
     * 设备读系统配置表，线程数经 Resolver 解析，返回 R.ok 包装的转码配置。
     */
    @Test
    void shouldReturnTranscodeConfigWithResolvedValues() throws Exception {
        when(transcodeConfigResolver.resolveHwaccel()).thenReturn("qsv");
        when(systemConfigService.getValue(TranscodeConfigResolver.CONFIG_KEY_DEVICE, "")).thenReturn("/dev/dri/renderD128");
        when(transcodeConfigResolver.resolveThreads()).thenReturn(4);

        mockMvc.perform(get("/jcloud/api/admin/media/transcode-config"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.hwaccel").value("qsv"),
                        jsonPath("$.data.device").value("/dev/dri/renderD128"),
                        jsonPath("$.data.threads").value(4));

        verify(transcodeConfigResolver).resolveHwaccel();
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
     * PUT /admin/media/transcode-config 请求体为空对象：hwaccel 缺失视为参数错误（BusinessException），
     * 经 GlobalExceptionHandler 包装为 R（body code=400），配置不落库。
     */
    @Test
    void shouldRejectEmptyDtoWithoutHwaccel() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/media/transcode-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                        jsonPath("$.msg").value("硬解方式不能为空"),
                        jsonPath("$.data").value(nullValue()));

        verify(systemConfigService, never()).setValue(any(), any());
    }

    /**
     * PUT /admin/media/transcode-config 传入非法硬解方式（auto 已被移除，乱码值同样被拒）：
     * 控制器抛 BusinessException(PARAM_ERROR)，经 GlobalExceptionHandler 包装为 R（body code=400，HTTP 状态仍 200），
     * 配置不落库。
     */
    @Test
    void shouldRejectInvalidHwaccelWithParamError() throws Exception {
        for (String value : List.of("auto", "cuda", "amf", "硬解")) {
            mockMvc.perform(put("/jcloud/api/admin/media/transcode-config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"hwaccel\":\"" + value + "\"}"))
                    .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                            jsonPath("$.msg").value("非法的硬解方式: " + value),
                            jsonPath("$.data").value(nullValue()));
        }
        verify(systemConfigService, never()).setValue(any(), any());
    }

    /**
     * PUT /admin/media/transcode-config：vaapi/qsv/nvenc/none 四档均正常保存（device 去空白、线程数透传）。
     */
    @Test
    void shouldSaveAllFourValidHwaccelValues() throws Exception {
        for (String value : List.of("vaapi", "qsv", "nvenc", "none")) {
            mockMvc.perform(put("/jcloud/api/admin/media/transcode-config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"hwaccel\":\"" + value
                                    + "\",\"device\":\" /dev/dri/renderD128 \",\"threads\":2}"))
                    .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));
        }

        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_HWACCEL), eq("vaapi"));
        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_HWACCEL), eq("qsv"));
        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_HWACCEL), eq("nvenc"));
        verify(systemConfigService).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_HWACCEL), eq("none"));
        verify(systemConfigService, times(4)).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_DEVICE),
                eq("/dev/dri/renderD128"));
        verify(systemConfigService, times(4)).setValue(eq(TranscodeConfigResolver.CONFIG_KEY_THREADS), eq("2"));
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
