package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.RemoteMountType;
import com.fleyx.jcloud.common.enums.RemoteSyncTaskStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.model.bo.WebDavConfig;
import com.fleyx.jcloud.model.convert.RemoteMountConvert;
import com.fleyx.jcloud.model.dto.RemoteMountPageQueryDto;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.RemoteMountUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.vo.RemoteMountDetailVo;
import com.fleyx.jcloud.model.vo.RemoteMountHealthVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.service.RemoteMountService;
import com.fleyx.jcloud.service.RemoteMountSyncService;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.util.IdUtil;
import com.fleyx.jcloud.util.RemoteConfigCrypto;
import com.fleyx.jcloud.util.RemotePathUtil;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 远程挂载服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteMountServiceImpl implements RemoteMountService {

    private static final String TYPE_FOLDER = "folder";
    private static final String SOURCE_REMOTE = "remote";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_ERROR = "error";

    private final RemoteMountMapper remoteMountMapper;
    private final FileMapper fileMapper;
    private final RemoteMountConvert remoteMountConvert;
    private final RemoteConfigCrypto remoteConfigCrypto;
    private final ObjectMapper objectMapper;
    private final RemoteMountSyncService remoteMountSyncService;
    private final RemoteProtocolAdapterFactory adapterFactory;
    private final UserReadWriteLock userReadWriteLock;

    @Override
    public IPage<RemoteMountVo> page(RemoteMountPageQueryDto dto, String userId) {
        LambdaQueryWrapper<RemoteMount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RemoteMount::getUserId, userId);
        wrapper.eq(RemoteMount::getDeleteAt, 0L);
        if (StringUtils.hasText(dto.getName())) {
            wrapper.like(RemoteMount::getName, dto.getName());
        }
        wrapper.orderByDesc(RemoteMount::getCreateTime);
        Page<RemoteMount> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        Page<RemoteMount> result = remoteMountMapper.selectPage(page, wrapper);
        return result.convert(remoteMountConvert::poToVo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RemoteMountVo save(RemoteMountSaveDto dto, String userId) {
        rejectDuplicateName(userId, dto.getName(), null);
        RemoteMount mount = remoteMountConvert.saveDtoToPo(dto);
        mount.setId(IdUtil.nextId());
        mount.setUserId(userId);
        mount.setConfig(buildConfigJson(dto, null));
        calcNextSyncTime(mount);
        remoteMountMapper.insert(mount);

        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            FileNode mountNode = createMountNode(mount, userId);
            fileMapper.insert(mountNode);
        } finally {
            lock.unlock();
        }

        remoteMountSyncService.submitImmediate(mount.getId(), userId);
        return remoteMountConvert.poToVo(mount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RemoteMountVo update(RemoteMountUpdateDto dto, String userId) {
        RemoteMount mount = requireOwnedMount(dto.getId(), userId);
        if (!mount.getName().equals(dto.getName())) {
            rejectDuplicateName(userId, dto.getName(), dto.getId());
        }
        remoteMountConvert.updateDtoToPo(dto, mount);
        mount.setConfig(buildConfigJson(dto, mount.getConfig()));
        calcNextSyncTime(mount);
        remoteMountMapper.updateById(mount);

        if (!mount.getName().equals(dto.getName())) {
            RLock lock = userReadWriteLock.writeLock(userId);
            lock.lock();
            try {
                FileNode mountNode = findMountNode(mount.getId(), userId);
                if (mountNode != null) {
                    mountNode.setName(dto.getName());
                    fileMapper.updateById(mountNode);
                }
            } finally {
                lock.unlock();
            }
        }
        return remoteMountConvert.poToVo(mount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, String userId) {
        RemoteMount mount = requireOwnedMount(id, userId);
        remoteMountMapper.deleteById(id);

        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            FileNode mountNode = findMountNode(id, userId);
            if (mountNode != null) {
                List<FileNode> subtree = fileMapper.selectByIdPathPrefix(userId, mountNode.getPath(), mountNode.getId());
                List<String> ids = new ArrayList<>(subtree.size());
                for (FileNode node : subtree) {
                    ids.add(node.getId());
                }
                if (!ids.isEmpty()) {
                    fileMapper.physicalDeleteByIds(ids);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RemoteMountDetailVo detail(String id, String userId) {
        RemoteMount mount = requireOwnedMount(id, userId);
        RemoteMountDetailVo vo = remoteMountConvert.poToDetailVo(mount);
        fillConfigToVo(mount.getConfig(), vo);
        return vo;
    }

    @Override
    public void testConnection(String id, String userId) {
        RemoteMount mount = requireOwnedMount(id, userId);
        RemoteProtocolAdapter adapter = adapterFactory.create(mount);
        WebDavConfig config = readConfig(mount.getConfig());
        adapter.exists(RemotePathUtil.buildRemotePath(config.getRootPath(), "/"));
    }

    @Override
    public void testConnection(RemoteMountSaveDto dto) {
        WebDavConfig config = new WebDavConfig();
        config.setUrl(dto.getUrl());
        config.setUsername(dto.getUsername());
        config.setPassword(dto.getPassword());
        config.setRootPath(dto.getRootPath());
        RemoteProtocolAdapter adapter = new WebDavProtocolAdapter(config);
        adapter.exists(RemotePathUtil.buildRemotePath(config.getRootPath(), "/"));
    }

    @Override
    public List<RemoteMountHealthVo> healthCheck(String userId) {
        LambdaQueryWrapper<RemoteMount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RemoteMount::getUserId, userId);
        wrapper.eq(RemoteMount::getDeleteAt, 0L);
        List<RemoteMount> mounts = remoteMountMapper.selectList(wrapper);
        List<RemoteMountHealthVo> result = new ArrayList<>(mounts.size());
        for (RemoteMount mount : mounts) {
            RemoteMountHealthVo vo = new RemoteMountHealthVo();
            vo.setId(mount.getId());
            vo.setName(mount.getName());
            try {
                testConnection(mount.getId(), userId);
                vo.setStatus(STATUS_OK);
                vo.setMessage("连接正常");
            } catch (Exception e) {
                vo.setStatus(STATUS_ERROR);
                vo.setMessage(e.getMessage());
            }
            result.add(vo);
        }
        return result;
    }

    private RemoteMount requireOwnedMount(String id, String userId) {
        RemoteMount mount = remoteMountMapper.selectById(id);
        if (mount == null || !mount.getUserId().equals(userId) || mount.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "远程挂载不存在");
        }
        return mount;
    }

    private void rejectDuplicateName(String userId, String name, String excludeId) {
        LambdaQueryWrapper<RemoteMount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RemoteMount::getUserId, userId);
        wrapper.eq(RemoteMount::getName, name);
        wrapper.eq(RemoteMount::getDeleteAt, 0L);
        if (excludeId != null) {
            wrapper.ne(RemoteMount::getId, excludeId);
        }
        if (remoteMountMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "挂载名称已存在");
        }
    }

    private String buildConfigJson(RemoteMountSaveDto dto, String existingConfig) {
        return doBuildConfigJson(dto.getUrl(), dto.getUsername(), dto.getPassword(), dto.getRootPath(), existingConfig);
    }

    private String buildConfigJson(RemoteMountUpdateDto dto, String existingConfig) {
        return doBuildConfigJson(dto.getUrl(), dto.getUsername(), dto.getPassword(), dto.getRootPath(), existingConfig);
    }

    private String doBuildConfigJson(String url, String username, String password, String rootPath, String existingConfig) {
        try {
            WebDavConfig config = new WebDavConfig();
            if (existingConfig != null) {
                config = readConfig(existingConfig);
            }
            config.setUrl(url);
            config.setUsername(username);
            if (password != null) {
                config.setPassword(remoteConfigCrypto.encrypt(password));
            }
            if (rootPath != null) {
                config.setRootPath(rootPath);
            }
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "序列化远程挂载配置失败", e);
        }
    }

    private WebDavConfig readConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, WebDavConfig.class);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "解析远程挂载配置失败", e);
        }
    }

    private void fillConfigToVo(String configJson, RemoteMountDetailVo vo) {
        try {
            WebDavConfig config = objectMapper.readValue(configJson, WebDavConfig.class);
            vo.setUrl(config.getUrl());
            vo.setUsername(config.getUsername());
            vo.setPassword(remoteConfigCrypto.decrypt(config.getPassword()));
            vo.setRootPath(config.getRootPath());
        } catch (Exception e) {
            log.warn("解析挂载配置到详情视图失败", e);
        }
    }

    private void calcNextSyncTime(RemoteMount mount) {
        if (!Integer.valueOf(1).equals(mount.getEnabled()) || !StringUtils.hasText(mount.getCronExpr())) {
            mount.setNextSyncTime(null);
            return;
        }
        try {
            CronExpression expression = CronExpression.parse(mount.getCronExpr());
            mount.setNextSyncTime(expression.next(LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("解析 cron 表达式失败，mountId={}", mount.getId(), e);
            mount.setNextSyncTime(null);
        }
    }

    private FileNode createMountNode(RemoteMount mount, String userId) {
        FileNode node = new FileNode();
        node.setId(mount.getId());
        node.setUserId(userId);
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setName(mount.getName());
        node.setType(TYPE_FOLDER);
        node.setSize(0L);
        node.setSourceType(SOURCE_REMOTE);
        node.setRemoteMountId(mount.getId());
        node.setPath(FileNodeConstants.ROOT_ID);
        node.setStatus(1);
        return node;
    }

    private FileNode findMountNode(String remoteMountId, String userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getRemoteMountId, remoteMountId);
        wrapper.eq(FileNode::getParentId, FileNodeConstants.ROOT_ID);
        wrapper.eq(FileNode::getSourceType, SOURCE_REMOTE);
        return fileMapper.selectOne(wrapper);
    }
}
