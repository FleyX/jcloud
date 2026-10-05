package com.fleyx.jcloud.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件 HTML 模板渲染支撑类单元测试：标题与正文转义、链接按钮可选、默认按钮文案。
 */
class MailTemplateSupportTest {

    private final MailTemplateSupport mailTemplateSupport = new MailTemplateSupport();

    /**
     * 无链接：标题与正文内联，不渲染按钮与默认文案。
     */
    @Test
    void shouldRenderTitleAndContentWithoutLink() {
        String html = mailTemplateSupport.buildHtml("标题", "正文内容", null, null);
        assertTrue(html.contains("标题"));
        assertTrue(html.contains("正文内容"));
        assertFalse(html.contains("查看详情"));
    }

    /**
     * 有链接：渲染按钮与自定义文案，正文换行转 br，脚本等特殊字符被转义。
     */
    @Test
    void shouldRenderOptionalLinkButtonAndEscape() {
        String html = mailTemplateSupport.buildHtml("标题", "第一行\n第二行", "https://example.com/x", "立即查看");
        assertTrue(html.contains("href=\"https://example.com/x\""));
        assertTrue(html.contains("立即查看"));
        assertTrue(html.contains("第一行<br>第二行"));
        assertFalse(html.contains("<script>"));

        String escaped = mailTemplateSupport.buildHtml("<script>alert(1)</script>", null, null, null);
        assertFalse(escaped.contains("<script>"));
    }
}
