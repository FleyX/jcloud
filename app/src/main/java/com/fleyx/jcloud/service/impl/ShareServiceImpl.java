package com.fleyx.jcloud.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.ShareItemMapper;
import com.fleyx.jcloud.mapper.ShareMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.convert.ShareConvert;
import com.fleyx.jcloud.model.dto.ShareCreateDto;
import com.fleyx.jcloud.model.dto.SharePageQueryDto;
import com.fleyx.jcloud.model.dto.ShareUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.Share;
import com.fleyx.jcloud.model.po.ShareItem;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.ShareDetailVo;
import com.fleyx.jcloud.model.vo.ShareVo;
import com.fleyx.jcloud.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分享业务实现。
 */
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private static final String SHARE_CODE_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int SHARE_CODE_LENGTH = 8;

    private final ShareMapper shareMapper;
    private final ShareItemMapper shareItemMapper;
    private final FileMapper fileMapper;
    private final ShareConvert shareConvert;
    private final FileConvert fileConvert;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareVo create(ShareCreateDto dto, String userId) {
        List<FileNode> nodes = validateNodes(dto.getFileNodeIds(), userId);
        Share share = buildShare(dto, userId);
        shareMapper.insert(share);
        insertItems(share.getId(), nodes);
        return shareConvert.poToVo(share);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareVo update(String shareId, ShareUpdateDto dto, String userId) {
        Share share = requireOwnShare(shareId, userId);
        List<FileNode> nodes = validateNodes(dto.getFileNodeIds(), userId);
        updateShareFields(share, dto);
        shareMapper.updateById(share);
        shareItemMapper.deleteByShareId(shareId);
        insertItems(shareId, nodes);
        return shareConvert.poToVo(share);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String shareId, String userId) {
        Share share = requireOwnShare(shareId, userId);
        share.setDeleteAt(System.currentTimeMillis());
        share.setStatus(CommonStatus.DISABLED.getCode());
        shareMapper.updateById(share);
    }

    @Override
    public ShareDetailVo detail(String shareId, String userId) {
        Share share = requireOwnShare(shareId, userId);
        return buildDetailVo(share);
    }

    @Override
    public IPage<ShareVo> page(SharePageQueryDto dto, String userId) {
        LambdaQueryWrapper<Share> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Share::getUserId, userId);
        wrapper.eq(Share::getDeleteAt, 0L);
        if (dto.getStatus() != null) {
            wrapper.eq(Share::getStatus, dto.getStatus());
        }
        if (dto.getName() != null && !dto.getName().isBlank()) {
            wrapper.like(Share::getName, dto.getName().trim());
        }
        wrapper.orderByDesc(Share::getCreateTime);
        Page<Share> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<Share> poPage = shareMapper.selectPage(page, wrapper);
        return poPage.convert(shareConvert::poToVo);
    }

    private Share requireOwnShare(String shareId, String userId) {
        Share share = shareMapper.selectById(shareId);
        if (share == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分享不存在");
        }
        if (!share.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该分享");
        }
        return share;
    }

    private List<FileNode> validateNodes(List<String> nodeIds, String userId) {
        if (CollectionUtils.isEmpty(nodeIds)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "至少选择一个文件或文件夹");
        }
        List<FileNode> nodes = fileMapper.selectBatchIds(nodeIds);
        if (nodes.size() != nodeIds.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部分文件或文件夹不存在");
        }
        for (FileNode node : nodes) {
            if (!userId.equals(node.getUserId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权分享该文件或文件夹");
            }
        }
        return nodes;
    }

    private Share buildShare(ShareCreateDto dto, String userId) {
        Share share = new Share();
        share.setUserId(userId);
        share.setName(dto.getName().trim());
        share.setDescription(dto.getDescription());
        share.setShareCode(generateUniqueShareCode());
        share.setPasswordHash(hashPassword(dto.getPassword()));
        share.setExpireAt(dto.getExpireAt());
        share.setMaxViews(dto.getMaxViews());
        share.setViewCount(0L);
        share.setStatus(CommonStatus.ENABLED.getCode());
        share.setDeleteAt(0L);
        return share;
    }

    private void updateShareFields(Share share, ShareUpdateDto dto) {
        share.setName(dto.getName().trim());
        share.setDescription(dto.getDescription());
        if (dto.getPassword() != null) {
            share.setPasswordHash(hashPassword(dto.getPassword().isBlank() ? null : dto.getPassword()));
        }
        share.setExpireAt(dto.getExpireAt());
        share.setMaxViews(dto.getMaxViews());
        if (dto.getStatus() != null) {
            share.setStatus(dto.getStatus());
        }
    }

    private void insertItems(String shareId, List<FileNode> nodes) {
        List<ShareItem> items = new ArrayList<>(nodes.size());
        for (FileNode node : nodes) {
            ShareItem item = new ShareItem();
            item.setShareId(shareId);
            item.setFileNodeId(node.getId());
            items.add(item);
        }
        for (ShareItem item : items) {
            shareItemMapper.insert(item);
        }
    }

    private ShareDetailVo buildDetailVo(Share share) {
        ShareDetailVo vo = new ShareDetailVo();
        ShareVo base = shareConvert.poToVo(share);
        vo.setId(base.getId());
        vo.setName(base.getName());
        vo.setDescription(base.getDescription());
        vo.setShareCode(base.getShareCode());
        vo.setHasPassword(base.getHasPassword());
        vo.setExpireAt(base.getExpireAt());
        vo.setMaxViews(base.getMaxViews());
        vo.setViewCount(base.getViewCount());
        vo.setStatus(base.getStatus());
        vo.setCreateTime(base.getCreateTime());
        vo.setUpdateTime(base.getUpdateTime());
        vo.setItems(loadItemVos(share.getId()));
        return vo;
    }

    private List<FileNodeVo> loadItemVos(String shareId) {
        List<ShareItem> items = shareItemMapper.selectByShareId(shareId);
        List<String> nodeIds = items.stream().map(ShareItem::getFileNodeId).toList();
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        return fileMapper.selectBatchIds(nodeIds).stream()
                .map(fileConvert::poToVo)
                .toList();
    }

    private String generateUniqueShareCode() {
        while (true) {
            String code = RandomUtil.randomString(SHARE_CODE_CHARS, SHARE_CODE_LENGTH);
            if (shareMapper.selectByShareCode(code) == null) {
                return code;
            }
        }
    }

    private String hashPassword(String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }
}
