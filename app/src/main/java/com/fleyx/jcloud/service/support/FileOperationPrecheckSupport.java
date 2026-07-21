package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.dto.FilePreCheckOperationDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件移动/复制冲突预检支撑组件。
 */
@Component
@RequiredArgsConstructor
public class FileOperationPrecheckSupport {

    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final FileMoveCopySupport fileMoveCopySupport;

    /**
     * 移动/复制前冲突预检。
     *
     * @param dto    预检参数
     * @param userId 用户 ID
     * @return 冲突列表
     */
    public List<ConflictItemVo> doPreCheck(FilePreCheckOperationDto dto, String userId) {
        String targetParentId = FileNodeUtil.normalizeParentId(dto.getTargetParentId());
        FileNode targetParent = fileMoveCopySupport.resolveTargetParentNode(targetParentId, userId);
        List<FileNode> sources = dto.getItems().stream()
                .map(item -> fileNodeSupport.getOwnedNode(item.getId(), userId))
                .toList();
        fileMoveCopySupport.validateSourceConsistency(sources, targetParent);
        fileMoveCopySupport.validateTargetNotSelfOrDescendant(sources, targetParent, targetParentId);

        String targetParentPathName = fileMoveCopySupport.resolveParentPathName(targetParentId, userId);
        List<ConflictItemVo> conflicts = new ArrayList<>();
        for (OperationItemDto item : dto.getItems()) {
            FileNode source = fileNodeSupport.getOwnedNode(item.getId(), userId);
            String targetName = StringUtils.hasText(item.getNewName())
                    ? item.getNewName().trim()
                    : source.getName();
            collectConflicts(source, targetName, targetParentId, targetParentPathName, userId, conflicts);
        }
        return conflicts;
    }

    private void collectConflicts(FileNode source, String targetName, String targetParentId,
                                  String targetParentPathName, String userId, List<ConflictItemVo> conflicts) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParentId, targetName);
        if (TYPE_FOLDER.equals(source.getType())) {
            if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                conflicts.add(buildAutoMergeConflictItem(source, existing, targetParentPathName));
                String currentPathName = FilePathUtil.buildPathName(targetParentPathName, targetName);
                List<FileNode> children = fileMapper.selectByParentId(userId, source.getId());
                for (FileNode child : children) {
                    collectConflicts(child, child.getName(), existing.getId(), currentPathName, userId, conflicts);
                }
            } else if (existing != null) {
                conflicts.add(buildConflictItem(source, existing, targetParentPathName));
            }
        } else if (existing != null) {
            conflicts.add(buildConflictItem(source, existing, targetParentPathName));
        }
    }

    private ConflictItemVo buildConflictItem(FileNode source, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(source, existing, targetParentPathName);
        vo.setAutoMerge(false);
        return vo;
    }

    private ConflictItemVo buildAutoMergeConflictItem(FileNode source, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(source, existing, targetParentPathName);
        vo.setType(TYPE_FOLDER);
        vo.setAutoMerge(true);
        return vo;
    }

    private ConflictItemVo buildBaseConflictItem(FileNode source, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(source.getId());
        vo.setSourceId(source.getId());
        vo.setSourceName(source.getName());
        vo.setSourceType(source.getType());
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(source.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        String sourcePath = filePathSupport.resolveNamePath(source, source.getUserId());
        vo.setSourcePath(sourcePath);
        vo.setTargetPath(FilePathUtil.buildPathName(targetParentPathName, existing.getName()));
        return vo;
    }
}
