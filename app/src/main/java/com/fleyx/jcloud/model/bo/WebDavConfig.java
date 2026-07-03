package com.fleyx.jcloud.model.bo;

import lombok.Data;

/**
 * WebDAV 协议配置对象。
 */
@Data
public class WebDavConfig {

    /**
     * WebDAV 服务端根 URL，如 http://example.com/webdav。
     */
    private String url;

    /**
     * 用户名。
     */
    private String username;

    /**
     * 密码。
     */
    private String password;
}
