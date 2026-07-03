package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.RemoteMountLock;
import com.fleyx.jcloud.util.RemotePathUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 远程文件上传/下载服务实现。
 */
@Service
@RequiredArgsConstructor
public class RemoteFileServiceImpl implements RemoteFileService {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final RemoteMountMapper remoteMountMapper;
    private final FileConvert fileConvert;
    private final RemoteProtocolAdapterFactory adapterFactory;
    private final RemoteMountLock remoteMountLock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo upload(MultipartFile file, FileNode parentNode, String userId, String finalName) {
        validateRemoteFolder(parentNode, userId);
        RemoteMount mount = requireMount(parentNode.getRemoteMountId(), userId);
        RLock lock = remoteMountLock.getLock(mount.getId());
        lock.lock();
        try {
            return doUpload(file, parentNode, mount, userId, finalName);
        } finally {
            lock.unlock();
        }
    }

    private FileNodeVo doUpload(MultipartFile file, FileNode parentNode, RemoteMount mount,
                                String userId, String finalName) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentNode.getId(), finalName);
        if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能覆盖文件夹");
        }

        String parentRemotePath = deriveRemotePath(parentNode, mount);
        String newRemotePath = FilePathUtil.buildPathName(parentRemotePath, finalName);

        RemoteProtocolAdapter adapter = adapterFactory.create(mount);
        try (InputStream is = file.getInputStream()) {
            adapter.upload(newRemotePath, is, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "读取上传文件失败", e);
        }

        if (existing != null) {
            fileMapper.deleteById(existing.getId());
        }

        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(parentNode.getId());
        node.setName(finalName);
        node.setType(TYPE_FILE);
        node.setSize(file.getSize());
        node.setHash(null);
        node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        node.setRemoteMountId(mount.getId());
        node.setPath(buildChildPath(parentNode));
        node.setMimeType(file.getContentType());
        node.setStatus(1);
        fileMapper.insert(node);

        return fileConvert.poToVo(node);
    }

    @Override
    public FileDownloadResult download(FileNode node, String userId) {
        if (!userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文件");
        }
        if (!TYPE_FILE.equals(node.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅支持下载文件");
        }
        if (!FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不是远程文件");
        }
        RemoteMount mount = requireMount(node.getRemoteMountId(), userId);
        String remotePath = deriveRemotePath(node, mount);
        RemoteProtocolAdapter adapter = adapterFactory.create(mount);
        InputStream inputStream = adapter.download(remotePath);
        return new FileDownloadResult(node.getName(), inputStream, node.getMimeType(), node.getSize());
    }

    @Override
    public UploadPreCheckVo preCheckUpload(FileUploadPreCheckDto dto, FileNode parentNode, String userId) {
        validateRemoteFolder(parentNode, userId);
        UploadPreCheckVo result = new UploadPreCheckVo();
        result.setConflicts(buildConflictItems(dto.getFileName(), userId, parentNode.getId()));
        result.setCandidates(List.of());
        return result;
    }

    @Override
    public FileNodeVo instantUpload(FileInstantUploadDto dto, FileNode parentNode, String userId, String finalName) {
        throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程目录不支持秒传");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo createFolder(FileNode parentNode, String name, String userId) {
        validateRemoteFolder(parentNode, userId);
        RemoteMount mount = requireMount(parentNode.getRemoteMountId(), userId);
        RLock lock = remoteMountLock.getLock(mount.getId());
        lock.lock();
        try {
            return doCreateFolder(parentNode, mount, userId, name);
        } finally {
            lock.unlock();
        }
    }

    private FileNodeVo doCreateFolder(FileNode parentNode, RemoteMount mount, String userId, String name) {
        if (FileConflictHelper.existsSameName(fileMapper, userId, parentNode.getId(), name, null)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "同名文件或文件夹已存在");
        }

        String parentRemotePath = deriveRemotePath(parentNode, mount);
        String newRemotePath = FilePathUtil.buildPathName(parentRemotePath, name);

        RemoteProtocolAdapter adapter = adapterFactory.create(mount);
        adapter.createFolder(newRemotePath);

        FileNode folder = new FileNode();
        folder.setUserId(userId);
        folder.setParentId(parentNode.getId());
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setHash(null);
        folder.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        folder.setRemoteMountId(mount.getId());
        folder.setPath(buildChildPath(parentNode));
        folder.setStatus(1);
        fileMapper.insert(folder);

        return fileConvert.poToVo(folder);
    }

    private List<ConflictItemVo> buildConflictItems(String fileName, String userId, String parentId) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentId, fileName);
        if (existing == null) {
            return List.of();
        }
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(existing.getId());
        vo.setSourceName(fileName);
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(existing.getType());
        vo.setSuggestedStrategy("keep");
        return List.of(vo);
    }

    private void validateRemoteFolder(FileNode parentNode, String userId) {
        if (parentNode == null || !userId.equals(parentNode.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        if (!TYPE_FOLDER.equals(parentNode.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
        if (!FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不是远程目录");
        }
    }

    private RemoteMount requireMount(String remoteMountId, String userId) {
        RemoteMount mount = remoteMountMapper.selectById(remoteMountId);
        if (mount == null || !userId.equals(mount.getUserId()) || mount.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "远程挂载不存在");
        }
        return mount;
    }

    private String deriveRemotePath(FileNode node, RemoteMount mount) {
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = queryAncestorNames(node.getUserId(), ancestorIds);
        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(null, node.getUserId(), cache);
        String fullNamePath = FilePathUtil.resolveNamePath(node, ctx);
        return RemotePathUtil.relativeNamePath(mount.getName(), fullNamePath);
    }

    private Map<String, String> queryAncestorNames(String userId, Set<String> ancestorIds) {
        Map<String, String> cache = new HashMap<>();
        if (ancestorIds.isEmpty()) {
            return cache;
        }
        List<FileNode> ancestors = fileMapper.selectBatchIds(ancestorIds);
        for (FileNode ancestor : ancestors) {
            if (userId.equals(ancestor.getUserId())) {
                cache.put(ancestor.getId(), ancestor.getName());
            }
        }
        return cache;
    }

    private String buildChildPath(FileNode parentNode) {
        if (FileNodeConstants.ROOT_ID.equals(parentNode.getId())) {
            return FileNodeConstants.ROOT_ID;
        }
        return parentNode.getPath() + FileNodeConstants.PATH_SEPARATOR + parentNode.getId();
    }
}
