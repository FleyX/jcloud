package com.fleyx.jcloud.model.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.InputStream;

/**
 * 文件预览结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreviewResult {

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * MIME 类型。
     */
    private String contentType;

    /**
     * 文件大小（字节）。
     */
    private long size;

    /**
     * 文件输入流。
     */
    private InputStream inputStream;
}
