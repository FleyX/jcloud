package com.fleyx.jcloud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.RemoteMountType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.bo.WebDavConfig;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.util.RemoteConfigCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 远程协议适配器工厂。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteProtocolAdapterFactory {

    private final RemoteConfigCrypto remoteConfigCrypto;
    private final ObjectMapper objectMapper;

    /**
     * 根据远程挂载配置创建对应的协议适配器。
     *
     * @param mount 远程挂载配置
     * @return 协议适配器
     */
    public RemoteProtocolAdapter create(RemoteMount mount) {
        String type = mount.getType();
        if (RemoteMountType.WEBDAV.getValue().equals(type)) {
            return createWebDavAdapter(mount);
        }
        throw new BusinessException(ResultCode.BUSINESS_ERROR, "不支持的远程协议: " + type);
    }

    private RemoteProtocolAdapter createWebDavAdapter(RemoteMount mount) {
        try {
            WebDavConfig config = objectMapper.readValue(mount.getConfig(), WebDavConfig.class);
            config.setPassword(remoteConfigCrypto.decrypt(config.getPassword()));
            return new WebDavProtocolAdapter(config);
        } catch (Exception e) {
            log.error("创建 WebDAV 适配器失败，mountId={}", mount.getId(), e);
            throw new SystemException(ResultCode.SYSTEM_ERROR, "远程挂载配置解析失败", e);
        }
    }
}
