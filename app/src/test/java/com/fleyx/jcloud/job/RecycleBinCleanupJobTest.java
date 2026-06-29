package com.fleyx.jcloud.job;

import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.UserService;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 回收站自动清理任务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecycleBinCleanupJobTest {

    @Autowired
    private RecycleBinCleanupJob cleanupJob;

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private RecycleRecordMapper recycleRecordMapper;

    @TempDir
    Path tempDir;

    @Test
    void shouldDeleteExpiredRecycleRecords() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("old.txt", "Old"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        List<com.fleyx.jcloud.model.vo.OperationResultVo> deleteResults =
                fileRecycleService.deleteToTrash(deleteDto, user.getId());
        String recordId = deleteResults.get(0).getNodeId();

        RecycleRecord record = recycleRecordMapper.selectById(recordId);
        record.setCreateTime(LocalDateTime.now().minusDays(31));
        recycleRecordMapper.updateById(record);

        cleanupJob.cleanup();

        assertNull(recycleRecordMapper.selectById(record.getId()));
    }

    @Test
    void shouldKeepRecentRecycleRecords() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("recent.txt", "Recent"), user.getId(), FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        List<com.fleyx.jcloud.model.vo.OperationResultVo> deleteResults =
                fileRecycleService.deleteToTrash(deleteDto, user.getId());
        String recordId = deleteResults.get(0).getNodeId();

        cleanupJob.cleanup();

        assertEquals(recordId, recycleRecordMapper.selectById(recordId).getId());
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    private UserWithSpace prepareUserWithStorageSpace() {
        Path spacePath = tempDir.resolve("space-" + System.nanoTime());
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        spaceDto.setType("USER");
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(10L);
        userDto.setQuotaUnit("GB");
        UserVo user = userService.saveUser(userDto);
        UserContext.set(new CurrentUser(user.getId(), user.getUsername()));

        return new UserWithSpace(user, spacePath);
    }

    private record UserWithSpace(UserVo user, Path spacePath) {
    }
}
