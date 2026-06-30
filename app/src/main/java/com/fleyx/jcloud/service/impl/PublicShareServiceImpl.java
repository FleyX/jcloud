package com.fleyx.jcloud.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.ShareItemMapper;
import com.fleyx.jcloud.mapper.ShareMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.Share;
import com.fleyx.jcloud.model.po.ShareItem;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.PublicShareVo;
import com.fleyx.jcloud.service.FileDownloadService;
import com.fleyx.jcloud.service.FilePreviewService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.PublicShareService;
import com.fleyx.jcloud.util.ShareAccessChecker;
import com.fleyx.jcloud.util.ShareTokenUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 公开分享业务实现。
 */
@Service
@RequiredArgsConstructor
public class PublicShareServiceImpl implements PublicShareService {

    private static final String TYPE_FOLDER = "folder";

    private final ShareMapper shareMapper;
    private final ShareItemMapper shareItemMapper;
    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final FileConvert fileConvert;
    private final ShareTokenUtil shareTokenUtil;
    private final ShareAccessChecker accessChecker;
    private final FileService fileService;
    private final FilePreviewService filePreviewService;
    private final FileDownloadService fileDownloadService;

    @Override
    public PublicShareVo getShare(String shareCode) {
        Share share = requireActiveShare(shareCode);
        if (!hasPassword(share)) {
            incrementViewCount(share);
        }
        return buildPublicVo(share, !hasPassword(share));
    }

    @Override
    public String validateAccess(String shareCode, String password) {
        Share share = requireActiveShare(shareCode);
        if (hasPassword(share)) {
            if (password == null || !BCrypt.checkpw(password, share.getPasswordHash())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "访问密码错误");
            }
        }
        incrementViewCount(share);
        return shareTokenUtil.generateToken(shareCode);
    }

    @Override
    public List<FileNodeVo> listItems(String shareCode, String parentId, String accessToken) {
        Share share = requireAccessibleShare(shareCode, accessToken);
        List<ShareItem> items = shareItemMapper.selectByShareId(share.getId());
        if (!StringUtils.hasText(parentId) || "0".equals(parentId)) {
            return loadTopItemVos(items);
        }
        if (!accessChecker.isAccessible(parentId, items)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文件夹");
        }
        FilePageQueryDto query = new FilePageQueryDto();
        query.setParentId(parentId);
        query.setPageSize(1000L);
        return fileService.list(query, share.getUserId()).getRecords();
    }

    @Override
    public FileDownloadResult downloadFile(String shareCode, String fileNodeId, String accessToken) {
        Share share = requireAccessibleShare(shareCode, accessToken);
        List<ShareItem> items = shareItemMapper.selectByShareId(share.getId());
        if (!accessChecker.isAccessible(fileNodeId, items)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权下载该文件");
        }
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null || !"file".equals(node.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅支持下载文件");
        }
        return fileService.download(fileNodeId, share.getUserId());
    }

    @Override
    public PreviewResult previewFile(String shareCode, String fileNodeId, PreviewType type, String accessToken) {
        Share share = requireAccessibleShare(shareCode, accessToken);
        List<ShareItem> items = shareItemMapper.selectByShareId(share.getId());
        if (!accessChecker.isAccessible(fileNodeId, items)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权预览该文件");
        }
        User owner = requireUser(share.getUserId());
        return filePreviewService.previewByOwner(fileNodeId, share.getUserId(), owner.getUsername(), type);
    }

    @Override
    public BatchDownloadResult downloadBatch(String shareCode, FileBatchDownloadDto dto, String accessToken) {
        Share share = requireAccessibleShare(shareCode, accessToken);
        List<ShareItem> items = shareItemMapper.selectByShareId(share.getId());
        if (CollectionUtils.isEmpty(dto.getIds()) || !allAccessible(dto.getIds(), items)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "包含无权下载的文件");
        }
        User owner = requireUser(share.getUserId());
        return fileDownloadService.downloadBatchByOwner(dto, share.getUserId(), owner.getUsername());
    }

    @Override
    public FileZipTask getBatchTask(String shareCode, String taskId, String accessToken) {
        Share share = requireAccessibleShare(shareCode, accessToken);
        return fileDownloadService.getTask(taskId, share.getUserId());
    }

    @Override
    public FileDownloadResult downloadBatchResult(String shareCode, String taskId, String accessToken) {
        Share share = requireAccessibleShare(shareCode, accessToken);
        return fileDownloadService.downloadTaskResult(taskId, share.getUserId());
    }

    private Share requireActiveShare(String shareCode) {
        Share share = shareMapper.selectByShareCode(shareCode);
        if (share == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分享不存在或已失效");
        }
        if (share.getStatus() != CommonStatus.ENABLED.getCode() || share.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分享不存在或已失效");
        }
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分享已过期");
        }
        if (share.getMaxViews() != null && share.getViewCount() != null
                && share.getViewCount() >= share.getMaxViews()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分享访问次数已达上限");
        }
        return share;
    }

    private Share requireAccessibleShare(String shareCode, String accessToken) {
        Share share = requireActiveShare(shareCode);
        if (hasPassword(share)) {
            validateToken(accessToken, shareCode);
        }
        return share;
    }

    private void validateToken(String accessToken, String shareCode) {
        if (!StringUtils.hasText(accessToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "需要访问密码");
        }
        try {
            Claims claims = shareTokenUtil.parseToken(accessToken);
            if (!shareCode.equals(shareTokenUtil.getShareCode(claims))) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "访问凭证无效");
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "访问凭证无效或已过期");
        }
    }

    private void incrementViewCount(Share share) {
        share.setViewCount(share.getViewCount() == null ? 1L : share.getViewCount() + 1);
        shareMapper.updateById(share);
    }

    private boolean hasPassword(Share share) {
        return share.getPasswordHash() != null && !share.getPasswordHash().isBlank();
    }

    private List<FileNodeVo> loadTopItemVos(List<ShareItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Set<String> itemIds = items.stream()
                .map(ShareItem::getFileNodeId)
                .collect(java.util.stream.Collectors.toSet());
        List<FileNode> nodes = fileMapper.selectBatchIds(itemIds);
        return nodes.stream()
                .map(fileConvert::poToVo)
                .toList();
    }

    private boolean allAccessible(List<String> fileNodeIds, List<ShareItem> items) {
        for (String fileNodeId : fileNodeIds) {
            if (!accessChecker.isAccessible(fileNodeId, items)) {
                return false;
            }
        }
        return true;
    }

    private User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件所有者不存在");
        }
        return user;
    }

    private PublicShareVo buildPublicVo(Share share, boolean includeItems) {
        PublicShareVo vo = new PublicShareVo();
        vo.setId(share.getId());
        vo.setName(share.getName());
        vo.setDescription(share.getDescription());
        vo.setHasPassword(hasPassword(share));
        if (includeItems) {
            vo.setItems(loadTopItemVos(shareItemMapper.selectByShareId(share.getId())));
        } else {
            vo.setItems(new ArrayList<>());
        }
        return vo;
    }
}
