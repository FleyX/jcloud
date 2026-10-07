package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.model.dto.ShareCreateDto;
import com.fleyx.jcloud.model.dto.SharePageQueryDto;
import com.fleyx.jcloud.model.dto.ShareUpdateDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.PublicShareVo;
import com.fleyx.jcloud.model.vo.ShareDetailVo;
import com.fleyx.jcloud.model.vo.ShareVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享服务测试。
 */
@Transactional
class ShareServiceTest extends IntegrationTestBase {

    @Autowired
    private ShareService shareService;

    @Autowired
    private PublicShareService publicShareService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private AuthProperties authProperties;

    /**
     * 用例级随机客户端 IP，避免 Redis IP 窗口计数跨用例串扰。
     */
    private final String testIp = uniqueIp();

    private static String uniqueIp() {
        int a = Integer.parseInt(UUID.randomUUID().toString().replace("-", "").substring(0, 2), 16);
        int b = Integer.parseInt(UUID.randomUUID().toString().replace("-", "").substring(0, 2), 16);
        return "10.98." + a + "." + b;
    }

    @Test
    void shouldCreateShareWithMultipleFiles() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file1 = uploadFile(user.getId(), "a.txt", "hello");
        FileNodeVo file2 = uploadFile(user.getId(), "b.txt", "world");

        ShareCreateDto dto = new ShareCreateDto();
        dto.setName("我的分享");
        dto.setDescription("测试分享");
        dto.setFileNodeIds(List.of(file1.getId(), file2.getId()));

        ShareVo share = shareService.create(dto, user.getId());

        assertNotNull(share.getId());
        assertNotNull(share.getShareCode());
        assertEquals(8, share.getShareCode().length());
        assertEquals("我的分享", share.getName());
        assertEquals("测试分享", share.getDescription());
        assertFalse(share.getHasPassword());
        assertEquals(1, share.getStatus());

        ShareDetailVo detail = shareService.detail(share.getId(), user.getId());
        assertEquals(2, detail.getItems().size());
    }

    @Test
    void shouldCreateShareWithPassword() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = uploadFile(user.getId(), "secret.txt", "secret");

        ShareCreateDto dto = new ShareCreateDto();
        dto.setName("加密分享");
        dto.setFileNodeIds(List.of(file.getId()));
        dto.setPassword("123456");

        ShareVo share = shareService.create(dto, user.getId());

        assertTrue(share.getHasPassword());

        PublicShareVo publicShare = publicShareService.getShare(share.getShareCode());
        assertTrue(publicShare.getHasPassword());
        assertTrue(publicShare.getItems().isEmpty());

        String token = publicShareService.validateAccess(share.getShareCode(), "123456", null);
        assertNotNull(token);

        List<FileNodeVo> items = publicShareService.listItems(share.getShareCode(), null, token);
        assertEquals(1, items.size());
    }

    @Test
    void shouldUpdateShareItemsAndPassword() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file1 = uploadFile(user.getId(), "a.txt", "hello");
        FileNodeVo file2 = uploadFile(user.getId(), "b.txt", "world");

        ShareCreateDto createDto = new ShareCreateDto();
        createDto.setName("原分享");
        createDto.setFileNodeIds(List.of(file1.getId()));
        ShareVo share = shareService.create(createDto, user.getId());

        ShareUpdateDto updateDto = new ShareUpdateDto();
        updateDto.setName("更新后分享");
        updateDto.setFileNodeIds(List.of(file1.getId(), file2.getId()));
        updateDto.setPassword("newpass");

        ShareVo updated = shareService.update(share.getId(), updateDto, user.getId());

        assertEquals("更新后分享", updated.getName());
        assertTrue(updated.getHasPassword());
        ShareDetailVo detail = shareService.detail(share.getId(), user.getId());
        assertEquals(2, detail.getItems().size());
    }

    @Test
    void shouldDeleteShareLogically() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = uploadFile(user.getId(), "a.txt", "hello");

        ShareCreateDto dto = new ShareCreateDto();
        dto.setName("待删除");
        dto.setFileNodeIds(List.of(file.getId()));
        ShareVo share = shareService.create(dto, user.getId());

        shareService.delete(share.getId(), user.getId());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> publicShareService.getShare(share.getShareCode()));
        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getResultCode().getCode());
    }

    @Test
    void shouldPageShares() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = uploadFile(user.getId(), "a.txt", "hello");

        for (int i = 0; i < 3; i++) {
            ShareCreateDto dto = new ShareCreateDto();
            dto.setName("分享" + i);
            dto.setFileNodeIds(List.of(file.getId()));
            shareService.create(dto, user.getId());
        }

        SharePageQueryDto query = new SharePageQueryDto();
        query.setPageNum(1L);
        query.setPageSize(10L);
        IPage<ShareVo> page = shareService.page(query, user.getId());

        assertEquals(3, page.getTotal());
    }

    @Test
    void shouldRejectShareWithOtherUserFiles() {
        UserWithSpace userA = prepareUserWithStorageSpace();
        UserWithSpace userB = prepareUserWithStorageSpace();
        FileNodeVo file = uploadFile(userA.user().getId(), "a.txt", "hello");

        ShareCreateDto dto = new ShareCreateDto();
        dto.setName("越权分享");
        dto.setFileNodeIds(List.of(file.getId()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> shareService.create(dto, userB.user().getId()));
        assertEquals(ResultCode.FORBIDDEN.getCode(), exception.getResultCode().getCode());
    }

    @Test
    void shouldRejectExpiredShareAccess() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = uploadFile(user.getId(), "a.txt", "hello");

        ShareCreateDto dto = new ShareCreateDto();
        dto.setName("过期分享");
        dto.setFileNodeIds(List.of(file.getId()));
        dto.setExpireAt(LocalDateTime.now().minusMinutes(1));
        ShareVo share = shareService.create(dto, user.getId());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> publicShareService.getShare(share.getShareCode()));
        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getResultCode().getCode());
    }

    @Test
    void shareAccessShouldLockAfterConsecutiveFailures() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = uploadFile(user.getId(), "a.txt", "hello");

        ShareCreateDto dto = new ShareCreateDto();
        dto.setName("限流分享");
        dto.setFileNodeIds(List.of(file.getId()));
        dto.setPassword("123456");
        ShareVo share = shareService.create(dto, user.getId());

        int maxFailures = authProperties.getRateLimit().getMaxFailures();
        for (int i = 0; i < maxFailures; i++) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> publicShareService.validateAccess(share.getShareCode(), "wrong-password", testIp));
            assertEquals("访问密码错误", ex.getMessage());
        }

        // 第 maxFailures+1 次即使密码正确也拒绝，文案明确
        BusinessException ex = assertThrows(BusinessException.class,
                () -> publicShareService.validateAccess(share.getShareCode(), "123456", testIp));
        assertEquals("尝试次数过多，请稍后再试", ex.getMessage());
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getResultCode().getCode());
    }

    @Test
    void shareAccessSuccessShouldClearFailureCount() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = uploadFile(user.getId(), "a.txt", "hello");

        ShareCreateDto dto = new ShareCreateDto();
        dto.setName("清零分享");
        dto.setFileNodeIds(List.of(file.getId()));
        dto.setPassword("123456");
        ShareVo share = shareService.create(dto, user.getId());
        String shareCode = share.getShareCode();

        // 先失败 2 次
        for (int i = 0; i < 2; i++) {
            assertThrows(BusinessException.class,
                    () -> publicShareService.validateAccess(shareCode, "wrong-password", testIp));
        }
        // 成功后计数清零
        assertNotNull(publicShareService.validateAccess(shareCode, "123456", testIp));
        // 再失败 1 次未达阈值，不锁定
        assertThrows(BusinessException.class,
                () -> publicShareService.validateAccess(shareCode, "wrong-password", testIp));
        assertNotNull(publicShareService.validateAccess(shareCode, "123456", testIp));
    }

    private FileNodeVo uploadFile(String userId, String name, String content) {
        MultipartFile file = new MockMultipartFile("file", name, "text/plain", content.getBytes());
        return fileService.upload(file, userId, FileNodeConstants.ROOT_ID, null);
    }
}
