package com.fleyx.jcloud.job;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收站自动清理任务测试。
 */
@Transactional
class RecycleBinCleanupJobTest extends IntegrationTestBase {

    @Autowired
    private RecycleBinCleanupJob cleanupJob;

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private FileService fileService;

    @Autowired
    private RecycleRecordMapper recycleRecordMapper;

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

    @Test
    void shouldFinishCleanupWithoutSideEffectWhenTrashIsEmpty() {
        prepareUserWithStorageSpace();

        long before = recycleRecordMapper.selectCount(null);
        cleanupJob.cleanup();
        long after = recycleRecordMapper.selectCount(null);

        // 回收站为空：任务正常结束，回收站表无任何变化
        assertEquals(before, after);
    }

    @Test
    void shouldCountFailedRecordAndContinueWhenPermanentDeleteFails() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        FileNodeVo file = fileService.upload(buildFile("clean.txt", "Clean"), user.getId(),
                FileNodeConstants.ROOT_ID, null);

        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(file.getId()));
        String recordId = fileRecycleService.deleteToTrash(deleteDto, user.getId()).get(0).getNodeId();

        RecycleRecord validRecord = recycleRecordMapper.selectById(recordId);
        validRecord.setCreateTime(LocalDateTime.now().minusDays(31));
        recycleRecordMapper.updateById(validRecord);

        // 构造一条过期但用户已不存在的记录：permanentDelete 内部 requireUser 抛 BusinessException，
        // 对应 RecycleBinCleanupJob#cleanup 的 catch(BusinessException) 容错分支
        RecycleRecord ghostRecord = new RecycleRecord();
        ghostRecord.setUserId("00000000000zz");
        ghostRecord.setName("ghost.txt");
        ghostRecord.setType("file");
        ghostRecord.setOriginalPathName("/ghost.txt");
        ghostRecord.setTotalSize(0L);
        ghostRecord.setStatus(1);
        recycleRecordMapper.insert(ghostRecord);
        ghostRecord.setCreateTime(LocalDateTime.now().minusDays(31));
        recycleRecordMapper.updateById(ghostRecord);

        cleanupJob.cleanup();

        // 正常记录被彻底删除（DB 记录与回收站物理文件均清理），失败记录保留且任务不中断
        assertNull(recycleRecordMapper.selectById(recordId));
        Path trashRoot = Path.of(userWithSpace.spacePath().toString(), StorageConstant.TRASH_DIR,
                user.getUsername(), recordId);
        assertTrue(Files.notExists(trashRoot));
        assertEquals(ghostRecord.getId(), recycleRecordMapper.selectById(ghostRecord.getId()).getId());
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }
}
