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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 其他媒体库新模型查询支撑组件（ADR 0021 / issue #19）：网格列表与详情查询走新表
 * （t_media_other），视图对象沿用现有 VO，保持响应结构兼容（前端适配在 issue #21）。
 * <p>
 * 网格装配走 {@link MediaItemQueryDriver} 共享管线（工单 08），本类仅保留类型差异层：
 * 文件级无元数据（NONE 状态、无海报、release/rating 排序回退 added），分页走 LambdaQueryWrapper。
 * 缩略图沿用现有文件预览机制：网格卡片带 fileNodeId，前端按
 * /files/{fileNodeId}/preview?type=poster 取 ffmpeg 截图缩略图，行为与现状一致。
 */
@Component
@RequiredArgsConstructor
public class MediaOtherQuerySupport implements MediaItemQueryStrategy<MediaOther, String, MediaItemVo> {

    private final MediaOtherMapper mediaOtherMapper;
    private final FileMapper fileMapper;
    private final MediaFavoriteService mediaFavoriteService;
    private final MediaItemQueryDriver queryDriver;

    /**
     * 其他网格列表：文件级一行一卡片，支持关键词（条目名）、媒体库过滤与排序（title 按条目名，其余按添加时间）。
     */
    public IPage<MediaItemVo> listOthers(String userId, MediaPageQueryDto query) {
        return queryDriver.run(this, userId, query);
    }

    @Override
    public IPage<MediaOther> page(String userId, MediaPageQueryDto query) {
        Page<MediaOther> page = new Page<>(query.normalizedPageNum(), query.normalizedPageSize());
        LambdaQueryWrapper<MediaOther> wrapper = new LambdaQueryWrapper<MediaOther>()
                .eq(MediaOther::getUserId, userId)
                .eq(MediaItemVoSupport.blankToNull(query.getDirectoryId()) != null, MediaOther::getDirectoryId,
                        MediaItemVoSupport.blankToNull(query.getDirectoryId()))
                .like(MediaItemVoSupport.blankToNull(query.getKeyword()) != null, MediaOther::getName,
                        MediaItemVoSupport.blankToNull(query.getKeyword()));
        // 排序：title 按条目名（升/降序 + id 同向兜底）；其余（含 release/rating）对无元数据库不生效，维持添加时间语义
        if (query.sortByTitle()) {
            orderByName(wrapper, query.asc());
        } else {
            orderByCreateTime(wrapper, query.asc());
        }
        return mediaOtherMapper.selectPage(page, wrapper);
    }

    @Override
    public String metadataIdOf(MediaOther row) {
        // 其他条目文件级无元数据
        return null;
    }

    @Override
    public Map<String, String> representativeFiles(List<MediaOther> rows) {
        Map<String, String> result = new HashMap<>();
        for (MediaOther row : rows) {
            if (row.getFileNodeId() != null) {
                result.put(row.getId(), row.getFileNodeId());
            }
        }
        return result;
    }

    @Override
    public List<String> fileNodeIdsOf(Map<String, String> representativeFiles) {
        return representativeFiles.values().stream().filter(Objects::nonNull).distinct().toList();
    }

    @Override
    public List<String> ownerIdsOf(List<MediaOther> rows) {
        return rows.stream().map(MediaOther::getId).toList();
    }

    @Override
    public MediaItemVo toCard(MediaOther row, MediaItemAssemblyContext<String> ctx) {
        MediaItemVo vo = new MediaItemVo();
        vo.setId(row.getId());
        vo.setFileNodeId(row.getFileNodeId());
        vo.setItemType(MediaItemType.OTHER.getCode());
        vo.setFileName(ctx.fileNameOf(row.getFileNodeId()));
        vo.setMatchStatus(MediaMatchStatus.NONE.getCode());
        vo.setTitle(row.getName());
        vo.setDurationMs(row.getDurationMs());
        vo.setProgressMs(row.getProgressMs());
        vo.setLastPlayTime(row.getLastPlayTime());
        vo.setFavorited(ctx.isFavorited(row.getId()));
        return vo;
    }

    @Override
    public MediaFavoriteOwnerType favoriteOwner() {
        return MediaFavoriteOwnerType.OTHER;
    }

    /**
     * 按条目名 + id 同向排序。
     */
    private void orderByName(LambdaQueryWrapper<MediaOther> wrapper, boolean asc) {
        if (asc) {
            wrapper.orderByAsc(MediaOther::getName).orderByAsc(MediaOther::getId);
        } else {
            wrapper.orderByDesc(MediaOther::getName).orderByDesc(MediaOther::getId);
        }
    }

    /**
     * 按添加时间 + id 同向排序（缺省语义）。
     */
    private void orderByCreateTime(LambdaQueryWrapper<MediaOther> wrapper, boolean asc) {
        if (asc) {
            wrapper.orderByAsc(MediaOther::getCreateTime).orderByAsc(MediaOther::getId);
        } else {
            wrapper.orderByDesc(MediaOther::getCreateTime).orderByDesc(MediaOther::getId);
        }
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
}
