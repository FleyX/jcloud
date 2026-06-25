package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.po.StorageSpace;

/**
 * 系统存储空间提供者。
 */
public interface SystemStorageSpaceProvider {

    /**
     * 获取当前生效的系统存储空间。
     *
     * @return 系统存储空间
     */
    StorageSpace getSystemSpace();
}
