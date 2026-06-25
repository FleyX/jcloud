package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.UserService;
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
        FileNodeVo file = fileService.upload(buildFile("old.txt", "Old"), user.getId());

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        RecycleRecord record = recycleRecordMapper.selectById(getRecycleRecordId(file.getId(), user.getId()));
        record.setCreateTime(LocalDateTime.now().minusDays(31));
        recycleRecordMapper.updateById(record);

        cleanupJob.cleanup();

        assertNull(recycleRecordMapper.selectById(record.getId()));
    }

    @Test
    void shouldKeepRecentRecycleRecords() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("recent.txt", "Recent"), user.getId());

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        fileRecycleService.deleteToTrash(deleteDto, user.getId());

        cleanupJob.cleanup();

        Long recordId = getRecycleRecordId(file.getId(), user.getId());
        assertEquals(recordId, recycleRecordMapper.selectById(recordId).getId());
    }

    private Long getRecycleRecordId(Long nodeId, Long userId) {
        LambdaQueryWrapper<RecycleRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RecycleRecord::getNodeId, nodeId).eq(RecycleRecord::getUserId, userId);
        RecycleRecord record = recycleRecordMapper.selectOne(wrapper);
        return record == null ? null : record.getId();
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
        spaceDto.setCapacity(107374182400L);
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("cleanupUser" + System.nanoTime());
        userDto.setPassword("123456");
        UserVo user = userService.saveUser(userDto);

        UserStorageDto bindDto = new UserStorageDto();
        bindDto.setUserId(user.getId());
        bindDto.setStorageSpaceId(space.getId());
        bindDto.setQuota(10737418240L);
        userService.bindStorageSpace(bindDto);

        return new UserWithSpace(user, spacePath);
    }

    private record UserWithSpace(UserVo user, Path spacePath) {
    }
}
