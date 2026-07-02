package com.fleyx.jcloud.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 远程挂载协议类型。
 */
@Getter
@RequiredArgsConstructor
public enum RemoteMountType {

    /**
     * WebDAV 协议。
     */
    WEBDAV("webdav"),

    /**
     * S3 协议。
     */
    S3("s3"),

    /**
     * NFS 协议。
     */
    NFS("nfs");

    private final String value;
}
