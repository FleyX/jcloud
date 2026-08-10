package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.po.ShareItem;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShareAccessChecker 分享访问权限检查器测试。
 */
@Transactional
class ShareAccessCheckerTest extends IntegrationTestBase {

    @Autowired
    private ShareAccessChecker shareAccessChecker;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOperationService fileOperationService;

    @Test
    void nullOrEmptyItemsShouldNotBeAccessible() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo file = uploadFile(user.getId(), FileNodeConstants.ROOT_ID, "a.txt");

        assertFalse(shareAccessChecker.isAccessible(file.getId(), null));
        assertFalse(shareAccessChecker.isAccessible(file.getId(), List.of()));
    }

    @Test
    void fileDirectlyInShareItemsShouldBeAccessible() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo file = uploadFile(user.getId(), FileNodeConstants.ROOT_ID, "a.txt");

        boolean accessible = shareAccessChecker.isAccessible(file.getId(), List.of(shareItem(file.getId())));

        assertTrue(accessible);
    }

    @Test
    void descendantOfSharedFolderShouldBeAccessible() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "共享目录");
        FileNodeVo childFile = uploadFile(user.getId(), folder.getId(), "child.txt");
        FileNodeVo subFolder = createFolder(user.getId(), folder.getId(), "子目录");
        FileNodeVo grandChildFile = uploadFile(user.getId(), subFolder.getId(), "grand.txt");

        // 断言落库的祖先路径：'.' 分隔的祖先 id 列表，不含自身 id
        assertEquals(FileNodeConstants.ROOT_ID + "." + folder.getId(),
                fileMapper.selectById(childFile.getId()).getPath());
        assertEquals(FileNodeConstants.ROOT_ID + "." + folder.getId() + "." + subFolder.getId(),
                fileMapper.selectById(grandChildFile.getId()).getPath());

        List<ShareItem> items = List.of(shareItem(folder.getId()));

        assertTrue(shareAccessChecker.isAccessible(childFile.getId(), items));
        assertTrue(shareAccessChecker.isAccessible(grandChildFile.getId(), items));
    }

    @Test
    void fileOutsideSharedFolderShouldNotBeAccessible() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo sharedFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "共享目录");
        FileNodeVo otherFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "其他目录");
        FileNodeVo outsideFile = uploadFile(user.getId(), otherFolder.getId(), "outside.txt");

        boolean accessible = shareAccessChecker.isAccessible(outsideFile.getId(),
                List.of(shareItem(sharedFolder.getId())));

        assertFalse(accessible);
    }

    @Test
    void nonexistentFileNodeIdShouldNotBeAccessible() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "共享目录");

        boolean accessible = shareAccessChecker.isAccessible("nonexistent-node-id",
                List.of(shareItem(folder.getId())));

        assertFalse(accessible);
    }

    @Test
    void descendantNotAccessibleWhenShareItemsContainOnlyFiles() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "目录");
        FileNodeVo sharedFile = uploadFile(user.getId(), folder.getId(), "shared.txt");
        FileNodeVo siblingFile = uploadFile(user.getId(), folder.getId(), "sibling.txt");

        // 分享项只含文件（无文件夹），兄弟文件无法通过祖先路径匹配命中
        List<ShareItem> items = List.of(shareItem(sharedFile.getId()));

        assertTrue(shareAccessChecker.isAccessible(sharedFile.getId(), items));
        assertFalse(shareAccessChecker.isAccessible(siblingFile.getId(), items));
    }

    @Test
    void extractTopFolderIdsShouldReturnOnlyFolders() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folderA = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "目录A");
        FileNodeVo folderB = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "目录B");
        FileNodeVo file = uploadFile(user.getId(), FileNodeConstants.ROOT_ID, "a.txt");

        assertEquals(Set.of(), shareAccessChecker.extractTopFolderIds(null));
        assertEquals(Set.of(), shareAccessChecker.extractTopFolderIds(List.of()));

        List<ShareItem> mixed = List.of(
                shareItem(folderA.getId()),
                shareItem(file.getId()),
                shareItem(folderB.getId()),
                shareItem("nonexistent-node-id"));

        assertEquals(Set.of(folderA.getId(), folderB.getId()),
                shareAccessChecker.extractTopFolderIds(mixed));
    }

    private FileNodeVo createFolder(String userId, String parentId, String name) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }

    private FileNodeVo uploadFile(String userId, String parentId, String name) {
        MultipartFile file = new MockMultipartFile("file", name, "text/plain",
                "share access checker test".getBytes(StandardCharsets.UTF_8));
        return fileService.upload(file, userId, parentId, null);
    }

    private static ShareItem shareItem(String fileNodeId) {
        ShareItem item = new ShareItem();
        item.setFileNodeId(fileNodeId);
        return item;
    }
}
