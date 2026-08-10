package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 媒体扫描类集成测试基类：在通用基类之上提供媒体库用例共用的上传/建文件夹样板。
 */
abstract class MediaScanTestBase extends IntegrationTestBase {

    @Autowired
    protected FileService fileService;

    @Autowired
    protected FileOperationService fileOperationService;

    protected FileNodeVo upload(String userId, String parentId, String name) {
        MultipartFile file = new MockMultipartFile("file", name, "video/x-matroska", "video".getBytes());
        return fileService.upload(file, userId, parentId, null);
    }

    protected FileNodeVo createFolder(String userId, String parentId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }
}
