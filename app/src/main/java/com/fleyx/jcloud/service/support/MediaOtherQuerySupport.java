package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.service.MediaFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 其他媒体库新模型查询支撑组件（ADR 0021 / issue #19）：网格列表与详情查询走新表
 * （t_media_other），视图对象沿用现有 VO，保持响应结构兼容（前端适配在 issue #21）。
 * <p>
 * 缩略图沿用现有文件预览机制：网格卡片带 fileNodeId，前端按
 * /files/{fileNodeId}/preview?type=poster 取 ffmpeg 截图缩略图，行为与现状一致。
 */
@Component
@RequiredArgsConstructor
public class MediaOtherQuerySupport {

    private final MediaOtherMapper mediaOtherMapper;
    private final FileMapper fileMapper;
    private final MediaFavoriteService mediaFavoriteService;

    /**
     * 其他网格列表：文件级一行一卡片，支持关键词（条目名）与媒体库过滤，按添加时间排序。
     */
    public IPage<MediaItemVo> listOthers(String userId, MediaPageQueryDto query) {
        Page<MediaOther> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        LambdaQueryWrapper<MediaOther> wrapper = new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getUserId, userId)
                .eq(blankToNull(query.getDirectoryId()) != null, MediaOther::getDirectoryId,
                        blankToNull(query.getDirectoryId()))
                .like(blankToNull(query.getKeyword()) != null, MediaOther::getName,
                        blankToNull(query.getKeyword()));
        if (query.asc()) {
            wrapper.orderByAsc(MediaOther::getCreateTime).orderByAsc(MediaOther::getId);
        } else {
            wrapper.orderByDesc(MediaOther::getCreateTime).orderByDesc(MediaOther::getId);
        }
        IPage<MediaOther> result = mediaOtherMapper.selectPage(page, wrapper);
        List<MediaOther> rows = result.getRecords();
        Map<String, String> fileNameMap = loadFileNameMap(
                rows.stream().map(MediaOther::getFileNodeId).toList());
        List<MediaItemVo> vos = new ArrayList<>();
        for (MediaOther row : rows) {
            vos.add(toItemVo(row, fileNameMap.get(row.getFileNodeId())));
        }
        // 当前用户收藏状态批量填充（ownerType=OTHER）
        Set<String> favoritedIds = mediaFavoriteService.listFavoritedOwnerIds(userId, MediaFavoriteOwnerType.OTHER,
                rows.stream().map(MediaOther::getId).toList());
        for (MediaItemVo vo : vos) {
            vo.setFavorited(favoritedIds.contains(vo.getId()));
        }
        Page<MediaItemVo> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 其他详情：other 行文件级事实（ffprobe 结果、文件节点）。
     */
    public MediaItemDetailVo getOtherDetail(String otherId, String userId) {
        MediaOther row = mediaOtherMapper.selectById(otherId);
        if (row == null || !userId.equals(row.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体条目不存在");
        }
        FileNode node = fileMapper.selectById(row.getFileNodeId());
        MediaItemDetailVo vo = new MediaItemDetailVo();
        vo.setId(row.getId());
        vo.setItemType(MediaItemType.OTHER.getCode());
        vo.setFileName(node == null ? null : node.getName());
        vo.setFileSize(node == null ? null : node.getSize());
        vo.setMatchStatus(MediaMatchStatus.NONE.getCode());
        vo.setDurationMs(row.getDurationMs());
        vo.setProgressMs(row.getProgressMs());
        vo.setWidth(row.getWidth());
        vo.setHeight(row.getHeight());
        vo.setVideoCodec(row.getVideoCodec());
        vo.setAudioCodec(row.getAudioCodec());
        vo.setTitle(row.getName());
        vo.setGenres(List.of());
        vo.setFavorited(mediaFavoriteService.listFavoritedOwnerIds(userId, MediaFavoriteOwnerType.OTHER,
                List.of(otherId)).contains(otherId));
        return vo;
    }

    private MediaItemVo toItemVo(MediaOther row, String fileName) {
        MediaItemVo vo = new MediaItemVo();
        vo.setId(row.getId());
        vo.setFileNodeId(row.getFileNodeId());
        vo.setItemType(MediaItemType.OTHER.getCode());
        vo.setFileName(fileName);
        vo.setMatchStatus(MediaMatchStatus.NONE.getCode());
        vo.setTitle(row.getName());
        vo.setDurationMs(row.getDurationMs());
        vo.setProgressMs(row.getProgressMs());
        vo.setLastPlayTime(row.getLastPlayTime());
        return vo;
    }

    private Map<String, String> loadFileNameMap(List<String> fileNodeIds) {
        List<String> ids = fileNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(FileNode::getId, FileNode::getName));
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
