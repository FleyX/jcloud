package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 文件查询与读取支撑组件。
 */
@Component
@RequiredArgsConstructor
public class FileQuerySupport {

    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileConvert fileConvert;
    private final RemoteFileService remoteFileService;
    private final UserSpaceSupport userSpaceSupport;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;

    /**
     * 分页查询文件列表，按名称关键字搜索或按父节点分页。
     *
     * @param dto    查询参数
     * @param userId 用户 ID
     * @return 分页结果
     */
    public IPage<FileNodeVo> list(FilePageQueryDto dto, String userId) {
        if (StringUtils.hasText(dto.getName())) {
            return searchByName(dto, userId);
        }

        String parentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getParentId, parentId);
        applySort(wrapper, dto);

        Page<FileNode> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<FileNode> poPage = fileMapper.selectPage(page, wrapper);
        return poPage.convert(fileConvert::poToVo);
    }

    private IPage<FileNodeVo> searchByName(FilePageQueryDto dto, String userId) {
        String keyword = dto.getName().trim();
        String likePattern = escapeLikePattern(keyword);
        List<FileNode> records = fileMapper.searchByName(userId, keyword, likePattern);
        if (StringUtils.hasText(dto.getSortField())) {
            records.sort(buildComparator(dto));
        }
        Page<FileNodeVo> resultPage = new Page<>(dto.getPageNum(), dto.getPageSize());
        resultPage.setTotal(records.size());

        long offset = (dto.getPageNum() - 1) * dto.getPageSize();
        List<FileNodeVo> pageRecords = records.stream()
                .skip(offset)
                .limit(dto.getPageSize())
                .map(fileConvert::poToVo)
                .toList();
        resultPage.setRecords(pageRecords);
        return resultPage;
    }

    private String escapeLikePattern(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private void applySort(LambdaQueryWrapper<FileNode> wrapper, FilePageQueryDto dto) {
        String field = dto.getSortField();
        boolean asc = "asc".equalsIgnoreCase(dto.getSortOrder());
        if ("name".equals(field)) {
            if (asc) {
                wrapper.orderByAsc(FileNode::getName);
            } else {
                wrapper.orderByDesc(FileNode::getName);
            }
        } else if ("size".equals(field)) {
            if (asc) {
                wrapper.orderByAsc(FileNode::getSize);
            } else {
                wrapper.orderByDesc(FileNode::getSize);
            }
        } else {
            if (asc) {
                wrapper.orderByAsc(FileNode::getCreateTime);
            } else {
                wrapper.orderByDesc(FileNode::getCreateTime);
            }
        }
    }

    private Comparator<FileNode> buildComparator(FilePageQueryDto dto) {
        String field = dto.getSortField();
        boolean asc = "asc".equalsIgnoreCase(dto.getSortOrder());
        Comparator<FileNode> comparator;
        if ("name".equals(field)) {
            comparator = Comparator.comparing(FileNode::getName, Comparator.nullsFirst(String::compareTo));
        } else if ("size".equals(field)) {
            comparator = Comparator.comparing(FileNode::getSize, Comparator.nullsFirst(Long::compareTo));
        } else {
            comparator = Comparator.comparing(FileNode::getCreateTime, Comparator.nullsFirst(Comparator.naturalOrder()));
        }
        return asc ? comparator : comparator.reversed();
    }

    /**
     * 查询指定父节点下的子文件夹列表。
     *
     * @param parentId 父节点 ID
     * @param userId   用户 ID
     * @return 子文件夹列表
     */
    public List<FileNodeVo> listChildFolders(String parentId, String userId) {
        String normalizedParentId = FileNodeUtil.normalizeParentId(parentId);
        fileNodeSupport.validateTargetParent(normalizedParentId, userId);
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getParentId, normalizedParentId);
        wrapper.eq(FileNode::getType, TYPE_FOLDER);
        wrapper.orderByAsc(FileNode::getName);
        return fileMapper.selectList(wrapper).stream()
                .map(fileConvert::poToVo)
                .toList();
    }

    /**
     * 下载文件，返回文件内容流。
     *
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 下载结果
     */
    public FileDownloadResult download(String fileId, String userId) {
        User user = userSpaceSupport.requireUser(userId);
        FileNode node = fileMapper.selectById(fileId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在");
        }
        if (!node.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文件");
        }

        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            return remoteFileService.download(node, userId);
        }

        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }

        FilePathUtil.ResolveContext ctx = filePathSupport.buildResolveContext(node, user.getUsername(), space);
        Path physicalPath = FilePathUtil.resolvePhysicalPath(node, ctx);
        if (!Files.exists(physicalPath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件已丢失");
        }

        try {
            InputStream inputStream = Files.newInputStream(physicalPath);
            return new FileDownloadResult(node.getName(), inputStream, node.getMimeType(), node.getSize());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件读取失败", e);
        }
    }
}
