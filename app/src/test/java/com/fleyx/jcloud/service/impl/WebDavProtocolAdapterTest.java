package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.model.bo.WebDavConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WebDAV 协议适配器测试。
 */
class WebDavProtocolAdapterTest {

    @Test
    void shouldConvertAbsoluteHrefToRemotePath() throws Exception {
        WebDavConfig config = buildConfig("https://example.com/remote.php/webdav");
        WebDavProtocolAdapter adapter = new WebDavProtocolAdapter(config);

        assertEquals("/test", invokeToRemotePath(adapter, "/remote.php/webdav/test/"));
        assertEquals("/test/test1", invokeToRemotePath(adapter, "/remote.php/webdav/test/test1/"));
        assertEquals("/test/test1/test2", invokeToRemotePath(adapter, "/remote.php/webdav/test/test1/test2/"));
    }

    @Test
    void shouldConvertRelativeHrefToRemotePath() throws Exception {
        WebDavConfig config = buildConfig("https://example.com/remote.php/webdav");
        WebDavProtocolAdapter adapter = new WebDavProtocolAdapter(config);

        assertEquals("/test", invokeToRemotePath(adapter, "test/"));
        assertEquals("/test/test1", invokeToRemotePath(adapter, "test/test1/"));
    }

    @Test
    void shouldBuildFullUrl() throws Exception {
        WebDavConfig config = buildConfig("https://example.com/remote.php/webdav");
        WebDavProtocolAdapter adapter = new WebDavProtocolAdapter(config);

        assertEquals("https://example.com/remote.php/webdav/test",
                invokeBuildFullUrl(adapter, "/test"));
        assertEquals("https://example.com/remote.php/webdav/",
                invokeBuildFullUrl(adapter, "/"));
    }

    @Test
    void shouldBuildFullUrlForRootWebDav() throws Exception {
        WebDavConfig config = buildConfig("https://example.com/");
        WebDavProtocolAdapter adapter = new WebDavProtocolAdapter(config);

        assertEquals("https://example.com/test", invokeBuildFullUrl(adapter, "/test"));
        assertEquals("https://example.com/", invokeBuildFullUrl(adapter, "/"));
    }

    @Test
    void shouldTreatConfiguredSubFolderAsRemoteRoot() throws Exception {
        WebDavConfig config = buildConfig("https://example.com/remote.php/webdav/test");
        WebDavProtocolAdapter adapter = new WebDavProtocolAdapter(config);

        assertEquals("/", invokeToRemotePath(adapter, "/remote.php/webdav/test/"));
        assertEquals("/test1", invokeToRemotePath(adapter, "/remote.php/webdav/test/test1/"));
        assertEquals("/test1/test2", invokeToRemotePath(adapter, "/remote.php/webdav/test/test1/test2/"));

        assertEquals("https://example.com/remote.php/webdav/test/",
                invokeBuildFullUrl(adapter, "/"));
        assertEquals("https://example.com/remote.php/webdav/test/test1",
                invokeBuildFullUrl(adapter, "/test1"));
    }

    private WebDavConfig buildConfig(String url) {
        WebDavConfig config = new WebDavConfig();
        config.setUrl(url);
        config.setUsername("user");
        config.setPassword("pass");
        return config;
    }

    private String invokeToRemotePath(WebDavProtocolAdapter adapter, String href) throws Exception {
        Method method = WebDavProtocolAdapter.class.getDeclaredMethod("toRemotePath", String.class);
        method.setAccessible(true);
        return (String) method.invoke(adapter, href);
    }

    private String invokeBuildFullUrl(WebDavProtocolAdapter adapter, String remotePath) throws Exception {
        Method method = WebDavProtocolAdapter.class.getDeclaredMethod("buildFullUrl", String.class);
        method.setAccessible(true);
        return (String) method.invoke(adapter, remotePath);
    }
}
