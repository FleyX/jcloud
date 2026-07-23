package com.fleyx.jcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.bo.WebDavConfig;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.service.impl.WebDavProtocolAdapter;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import com.fleyx.jcloud.util.RemoteConfigCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 远程协议适配器工厂测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class RemoteProtocolAdapterFactoryTest {

    @Autowired
    private RemoteProtocolAdapterFactory adapterFactory;

    @Autowired
    private RemoteConfigCrypto remoteConfigCrypto;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRejectUnsupportedType() {
        RemoteMount mount = new RemoteMount();
        mount.setType("s3");

        assertThrows(BusinessException.class, () -> adapterFactory.create(mount));
    }

    @Test
    void shouldThrowSystemExceptionWithCauseWhenConfigBroken() {
        RemoteMount mount = new RemoteMount();
        mount.setType("webdav");
        mount.setConfig("{bad json");

        SystemException e = assertThrows(SystemException.class, () -> adapterFactory.create(mount));
        assertNotNull(e.getCause());
    }

    @Test
    void shouldCreateWebDavAdapter() throws Exception {
        WebDavConfig config = new WebDavConfig();
        config.setUrl("http://example.com/dav");
        config.setUsername("user");
        config.setPassword(remoteConfigCrypto.encrypt("secret"));
        RemoteMount mount = new RemoteMount();
        mount.setType("webdav");
        mount.setConfig(objectMapper.writeValueAsString(config));

        RemoteProtocolAdapter adapter = adapterFactory.create(mount);

        assertInstanceOf(WebDavProtocolAdapter.class, adapter);
    }
}
