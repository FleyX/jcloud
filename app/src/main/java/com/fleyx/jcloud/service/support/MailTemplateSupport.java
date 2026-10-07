package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import org.springframework.stereotype.Component;

/**
 * 邮件 HTML 模板渲染支撑类：统一中文邮件模板（标题 + 正文 + 可选链接按钮）。
 * <p>
 * 通知邮件渠道与邮件分享共用同一渲染逻辑，避免业务服务依赖渠道对象。
 */
@Component
public class MailTemplateSupport {

    private static final String DEFAULT_LINK_TEXT = "查看详情";

    /**
     * 统一中文 HTML 邮件模板：标题 + 正文 + 可选链接按钮。
     *
     * @param title    标题
     * @param content  正文，可空
     * @param linkUrl  链接地址，为空时不渲染按钮
     * @param linkText 按钮文案，为空时使用默认文案
     * @return HTML 正文
     */
    public String buildHtml(String title, String content, String linkUrl, String linkText) {
        StringBuilder html = new StringBuilder(512);
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><body style=\"margin:0;padding:24px;")
                .append("background:#f5f5f5;font-family:Arial,'Microsoft YaHei',sans-serif;\">")
                .append("<div style=\"max-width:560px;margin:0 auto;background:#ffffff;border-radius:12px;padding:24px;\">")
                .append("<h2 style=\"margin:0 0 16px;font-size:18px;color:#1f2937;\">")
                .append(HtmlUtil.escape(title))
                .append("</h2>");
        if (StrUtil.isNotBlank(content)) {
            html.append("<p style=\"margin:0;font-size:14px;line-height:1.7;color:#4b5563;\">")
                    .append(HtmlUtil.escape(content).replace("\n", "<br>"))
                    .append("</p>");
        }
        if (StrUtil.isNotBlank(linkUrl)) {
            html.append("<div style=\"margin-top:24px;\"><a href=\"")
                    .append(HtmlUtil.escape(linkUrl))
                    .append("\" style=\"display:inline-block;padding:10px 20px;background:#2563eb;color:#ffffff;")
                    .append("border-radius:8px;text-decoration:none;font-size:14px;\">")
                    .append(HtmlUtil.escape(StrUtil.blankToDefault(linkText, DEFAULT_LINK_TEXT)))
                    .append("</a></div>");
        }
        html.append("<p style=\"margin:24px 0 0;font-size:12px;color:#9ca3af;\">本邮件由 jcloud 系统自动发送，请勿回复。</p>")
                .append("</div></body></html>");
        return html.toString();
    }
}
