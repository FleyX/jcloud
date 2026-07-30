package com.fleyx.jcloud.service.support;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.vo.MediaDirectorySourceVo;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 媒体库来源目录支撑组件：保存校验（存在性/跨库查重/同库重叠）、来源目录替换与视图组装。
 */
@Component
@RequiredArgsConstructor
public class MediaDirectorySourceSupport {

    private final MediaDirectorySourceMapper mediaDirectorySourceMapper;
    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final FileMapper fileMapper;

    /**
     * 校验来源目录集合，全部通过则返回对应的文件夹节点（与入参同序）。
     * <p>
     * 校验规则：均为当前用户的文件夹；列表内无重复；未被该用户其他媒体库占用；
     * 同一媒体库内来源目录之间不存在祖先/后代重叠（按物化路径判断）。
     *
     * @param fileNodeIds         来源目录文件夹节点 ID 列表
     * @param userId              用户 ID
     * @param excludeDirectoryId  跨库查重时排除的媒体库 ID（更新场景排除自身），可为 null
     * @return 文件夹节点列表（与入参同序）
     */
    public List<FileNode> validateSources(List<String> fileNodeIds, String userId, String excludeDirectoryId) {
        Set<String> dedup = new HashSet<>(fileNodeIds);
        if (dedup.size() != fileNodeIds.size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "来源目录存在重复文件夹");
        }
        List<FileNode> folders = new ArrayList<>();
        for (String fileNodeId : fileNodeIds) {
            FileNode folder = fileMapper.selectById(fileNodeId);
            if (folder == null || !userId.equals(folder.getUserId())
                    || !FileNodeConstants.TYPE_FOLDER.equals(folder.getType())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "所选文件夹不存在");
            }
            folders.add(folder);
        }
        checkCrossLibraryDuplicate(fileNodeIds, userId, excludeDirectoryId);
        checkOverlap(folders);
        return folders;
    }

    /**
     * 查询指定媒体库的来源目录。
     *
     * @param directoryId 媒体库 ID
     * @return 来源目录列表
     */
    public List<MediaDirectorySource> listByDirectoryId(String directoryId) {
        return mediaDirectorySourceMapper.selectList(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directoryId)
                .orderByAsc(MediaDirectorySource::getCreateTime));
    }

    /**
     * 按媒体库 ID 分组查询来源目录。
     *
     * @param directoryIds 媒体库 ID 集合
     * @return 媒体库 ID → 来源目录列表
     */
    public Map<String, List<MediaDirectorySource>> mapByDirectoryIds(Collection<String> directoryIds) {
        if (CollUtil.isEmpty(directoryIds)) {
            return Map.of();
        }
        return mediaDirectorySourceMapper.selectList(new LambdaQueryWrapper<MediaDirectorySource>()
                        .in(MediaDirectorySource::getDirectoryId, directoryIds)
                        .orderByAsc(MediaDirectorySource::getCreateTime))
                .stream().collect(Collectors.groupingBy(MediaDirectorySource::getDirectoryId));
    }

    /**
     * 应用来源目录变更：删除被移除的来源目录行，插入新增行，保留未变化的行（条目通过 source_id 引用）。
     *
     * @param directoryId    媒体库 ID
     * @param currentSources 当前来源目录行
     * @param newFileNodeIds 新的文件夹节点 ID 列表
     * @return 被移除的来源目录行
     */
    public List<MediaDirectorySource> applySourceChanges(String directoryId, List<MediaDirectorySource> currentSources,
                                                         List<String> newFileNodeIds) {
        Set<String> newSet = new HashSet<>(newFileNodeIds);
        Set<String> currentSet = currentSources.stream().map(MediaDirectorySource::getFileNodeId)
                .collect(Collectors.toSet());
        List<MediaDirectorySource> removed = currentSources.stream()
                .filter(s -> !newSet.contains(s.getFileNodeId())).toList();
        if (!removed.isEmpty()) {
            mediaDirectorySourceMapper.deleteBatchIds(removed.stream().map(MediaDirectorySource::getId).toList());
        }
        for (String fileNodeId : newFileNodeIds) {
            if (!currentSet.contains(fileNodeId)) {
                insertSource(directoryId, fileNodeId);
            }
        }
        return removed;
    }

    /**
     * 插入一条来源目录。
     *
     * @param directoryId 媒体库 ID
     * @param fileNodeId  文件夹节点 ID
     */
    public void insertSource(String directoryId, String fileNodeId) {
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directoryId);
        source.setFileNodeId(fileNodeId);
        mediaDirectorySourceMapper.insert(source);
    }

    /**
     * 删除媒体库全部来源目录。
     *
     * @param directoryId 媒体库 ID
     */
    public void deleteByDirectoryId(String directoryId) {
        mediaDirectorySourceMapper.delete(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directoryId));
    }

    /**
     * 组装来源目录视图（按 FileNode 解析文件夹名称与来源类型，缺失节点名称置空）。
     *
     * @param sources 来源目录列表
     * @return 来源目录视图列表
     */
    public List<MediaDirectorySourceVo> toSourceVos(List<MediaDirectorySource> sources) {
        if (CollUtil.isEmpty(sources)) {
            return List.of();
        }
        Map<String, FileNode> nodeMap = fileMapper.selectBatchIds(
                        sources.stream().map(MediaDirectorySource::getFileNodeId).distinct().toList())
                .stream().collect(Collectors.toMap(FileNode::getId, Function.identity()));
        List<MediaDirectorySourceVo> vos = new ArrayList<>();
        for (MediaDirectorySource source : sources) {
            FileNode node = nodeMap.get(source.getFileNodeId());
            MediaDirectorySourceVo vo = new MediaDirectorySourceVo();
            vo.setId(source.getId());
            vo.setFileNodeId(source.getFileNodeId());
            vo.setFolderName(node == null ? null : node.getName());
            vo.setSourceType(node == null ? null : node.getSourceType());
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 跨库查重：同一文件夹不能属于该用户的其他媒体库。
     */
    private void checkCrossLibraryDuplicate(List<String> fileNodeIds, String userId, String excludeDirectoryId) {
        List<MediaDirectorySource> occupied = mediaDirectorySourceMapper.selectList(
                new LambdaQueryWrapper<MediaDirectorySource>()
                        .in(MediaDirectorySource::getFileNodeId, fileNodeIds));
        if (occupied.isEmpty()) {
            return;
        }
        Set<String> directoryIds = occupied.stream().map(MediaDirectorySource::getDirectoryId)
                .filter(id -> !id.equals(excludeDirectoryId)).collect(Collectors.toSet());
        if (directoryIds.isEmpty()) {
            return;
        }
        Set<String> occupiedNodeIds = mediaDirectoryMapper.selectBatchIds(directoryIds).stream()
                .filter(d -> userId.equals(d.getUserId())).map(MediaDirectory::getId).collect(Collectors.toSet());
        boolean conflict = occupied.stream()
                .anyMatch(s -> occupiedNodeIds.contains(s.getDirectoryId()) && fileNodeIds.contains(s.getFileNodeId()));
        if (conflict) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "所选文件夹已添加为其他媒体库的来源目录");
        }
    }

    /**
     * 同库重叠校验：来源目录之间不允许存在祖先/后代关系（按物化路径判断）。
     */
    private void checkOverlap(List<FileNode> folders) {
        List<String> fullPaths = folders.stream().map(FilePathUtil::fullIdPath).toList();
        for (int i = 0; i < fullPaths.size(); i++) {
            for (int j = i + 1; j < fullPaths.size(); j++) {
                if (isAncestorPath(fullPaths.get(i), fullPaths.get(j))
                        || isAncestorPath(fullPaths.get(j), fullPaths.get(i))) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR, "来源目录之间不能存在包含关系");
                }
            }
        }
    }

    /**
     * 判断 ancestorFullPath 是否为 descendantFullPath 的祖先完整物化路径。
     */
    private boolean isAncestorPath(String ancestorFullPath, String descendantFullPath) {
        return descendantFullPath.startsWith(ancestorFullPath + FileNodeConstants.PATH_SEPARATOR);
    }
}
