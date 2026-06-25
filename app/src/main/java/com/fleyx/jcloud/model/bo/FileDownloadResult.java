package com.fleyx.jcloud.model.bo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.InputStream;

/**
 * 文件下载结果业务对象。
 */
@Data
@AllArgsConstructor
public class FileDownloadResult {

    /**
     * 文件名称。
     */
    private String fileName;

    /**
     * 文件输入流。
     */
    private InputStream inputStream;

    /**
     * MIME 类型。
     */
    private String contentType;

    /**
     * 文件大小（字节）。
     */
    private Long size;
}
