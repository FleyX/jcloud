package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.dto.FilePreCheckRestoreDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 回收站恢复预检支撑组件。
 */
@Component
@RequiredArgsConstructor
public class TrashRestorePrecheckSupport {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final StorageSpaceMapper storageSpaceMapper;
    private final UserSpaceSupport userSpaceSupport;
    private final FileNodeSupport fileNodeSupport;
    private final TrashRestoreSupport trashRestoreSupport;
    private final TrashRestorePathSupport trashRestorePathSupport;

    /**
     * 恢复前冲突预检。
     *
     * @param dto    预检参数
     * @param userId 用户 ID
     * @return 冲突列表
     */
    public List<ConflictItemVo> doPreCheckRestore(FilePreCheckRestoreDto dto, String userId) {
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        String username = user.getUsername();
        List<ConflictItemVo> conflicts = new ArrayList<>();
        for (String id : dto.getIds()) {
            RecycleRecord record = trashRestoreSupport.getOwnedRecord(id, userId);
            String targetParentId = trashRestorePathSupport.resolveRestoreParentId(record, userId);
            String targetParentPathName = trashRestorePathSupport.resolveRestorePathName(targetParentId, userId);
            collectRestoreConflicts(record, targetParentId, targetParentPathName, userId, username, space, conflicts);
        }
        return conflicts;
    }

    private void collectRestoreConflicts(RecycleRecord record, String targetParentId,
                                         String targetParentPathName, String userId,
                                         String username, StorageSpace space,
                                         List<ConflictItemVo> conflicts) {
        FileNode existing = fileNodeSupport.findExistingChild(userId, targetParentId, record.getName());
        if (TYPE_FOLDER.equals(record.getType())) {
            if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                conflicts.add(buildAutoMergeConflictItem(record, existing, targetParentPathName));
                String targetTopPathName = FilePathUtil.buildPathName(targetParentPathName, record.getName());
                walkTrashTreeForConflicts(record, existing, targetTopPathName, userId, username, space, conflicts);
            } else if (existing != null) {
                conflicts.add(buildConflictItem(record, existing, targetParentPathName));
            }
        } else if (existing != null) {
            conflicts.add(buildConflictItem(record, existing, targetParentPathName));
        }
    }

    private void walkTrashTreeForConflicts(RecycleRecord record, FileNode targetFolder,
                                           String targetTopPathName, String userId,
                                           String username, StorageSpace space,
                                           List<ConflictItemVo> conflicts) {
        Path trashRoot = FilePathUtil.resolveTrashRoot(space, username, record.getId());
        Path sourceTop = trashRoot.resolve(record.getName());
        if (!Files.exists(sourceTop)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(sourceTop)) {
            for (Path sourcePath : stream.toList()) {
                if (sourcePath.equals(sourceTop)) {
                    continue;
                }
                Path relative = sourceTop.relativize(sourcePath);
                String rel = relative.toString().replace(java.io.File.separator, "/");
                String targetPathName = FilePathUtil.buildPathName(targetTopPathName, rel);
                String parentPathName = FilePathUtil.parentOf(targetPathName);
                String name = sourcePath.getFileName().toString();
                FileNode parentFolder = fileNodeSupport.findFolderByPathName(userId, parentPathName);
                String parentId = parentFolder == null ? null : parentFolder.getId();
                if (parentId == null) {
                    continue;
                }
                if (Files.isDirectory(sourcePath)) {
                    FileNode existing = fileNodeSupport.findExistingChild(userId, parentId, name);
                    if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                        conflicts.add(buildAutoMergeConflictItem(record, existing, parentPathName));
                    } else if (existing != null) {
                        conflicts.add(buildConflictItem(record, existing, parentPathName));
                    }
                } else {
                    FileNode existingFile = fileNodeSupport.findExistingChild(userId, parentId, name);
                    if (existingFile != null) {
                        conflicts.add(buildTrashFileConflictItem(record, rel, targetPathName, existingFile));
                    }
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "扫描回收站目录失败");
        }
    }

    private ConflictItemVo buildTrashFileConflictItem(RecycleRecord record, String relativePath,
                                                      String targetPathName, FileNode existingFile) {
        String sourceFullPath = "/".equals(record.getOriginalPathName())
                ? "/" + relativePath
                : record.getOriginalPathName() + "/" + relativePath;
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(record.getId());
        vo.setSourceId(record.getId());
        vo.setSourceName(existingFile.getName());
        vo.setSourceType(TYPE_FILE);
        vo.setExistingId(existingFile.getId());
        vo.setExistingName(existingFile.getName());
        vo.setExistingType(existingFile.getType());
        vo.setType(TYPE_FILE);
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        vo.setSourcePath(sourceFullPath);
        vo.setTargetPath(targetPathName);
        vo.setAutoMerge(false);
        return vo;
    }

    private ConflictItemVo buildConflictItem(RecycleRecord record, FileNode existing,
                                             String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(record, existing, targetParentPathName);
        vo.setAutoMerge(false);
        return vo;
    }

    private ConflictItemVo buildAutoMergeConflictItem(RecycleRecord record, FileNode existing,
                                                      String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(record, existing, targetParentPathName);
        vo.setType(TYPE_FOLDER);
        vo.setAutoMerge(true);
        return vo;
    }

    private ConflictItemVo buildBaseConflictItem(RecycleRecord record, FileNode existing,
                                                 String targetParentPathName) {
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(record.getId());
        vo.setSourceId(record.getId());
        vo.setSourceName(record.getName());
        vo.setSourceType(record.getType());
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(record.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        vo.setSourcePath(record.getOriginalPathName());
        vo.setTargetPath(FilePathUtil.buildPathName(targetParentPathName, existing.getName()));
        return vo;
    }
}
