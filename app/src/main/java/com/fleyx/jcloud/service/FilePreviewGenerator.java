package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.PreviewType;

import java.nio.file.Path;

/**
 * 预览生成器。
 * <p>
 * 根据源文件路径生成对应类型的预览文件。
 */
public interface FilePreviewGenerator {

    /**
     * 返回支持的预览类型。
     *
     * @return 预览类型
     */
    PreviewType supportedType();

    /**
     * 生成预览文件到目标路径。
     *
     * @param sourcePath 源文件路径
     * @param targetPath 目标预览文件路径
     */
    void generate(Path sourcePath, Path targetPath) throws Exception;
}
