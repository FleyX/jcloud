package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.bo.TransferItem;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.TransferTask;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import com.fleyx.jcloud.service.impl.WebDavFileOperationHelper;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.RemoteMountLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 跨来源传输节点执行支撑组件。
 * <p>
 * 负责单节点的递归传输（源端下载 → 目标端上传/落盘）、
 * 目标端冲突处理与移动模式下的源删除。
 */
@Component
@RequiredArgsConstructor
public class TransferNodeSupport {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";
    private static final String OP_MOVE = "move";
    private static final long LOCK_WAIT_SECONDS = 30L;

    private final FileMapper fileMapper;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final UserSpaceSupport userSpaceSupport;
    private final RemoteMountSupport remoteMountSupport;
    private final RemoteProtocolAdapterFactory adapterFactory;
    private final RemoteMountLock remoteMountLock;
    private final RemoteFileOperationService remoteFileOperationService;
    private final WebDavFileOperationHelper webDavFileOperationHelper;
    private final TrashDeleteSupport trashDeleteSupport;

    /**
     * 传输一个顶层节点（含覆盖目标的替换删除）。
     *
     * @param item 传输项快照
     * @param task 传输任务
     * @param ctx  执行上下文
     */
    public void transferTopLevel(TransferItem item, TransferTask task, TransferContext ctx) {
        String userId = task.getUserId();
        FileNode source = fileMapper.selectById(item.getNodeId());
        if (source == null) {
            ctx.recordFailure(item.getName(), "源节点不存在或已被删除");
            return;
        }
        FileNode targetParent = FileNodeConstants.ROOT_ID.equals(task.getTargetParentId())
                ? null : fileMapper.selectById(task.getTargetParentId());
        if (!FileNodeConstants.ROOT_ID.equals(task.getTargetParentId()) && targetParent == null) {
            ctx.recordFailure(item.getName(), "目标目录不存在或已被删除");
            return;
        }
        if (item.getReplaceExistingId() != null) {
            FileNode existing = fileMapper.selectById(item.getReplaceExistingId());
            if (existing != null) {
                deleteTargetNode(existing, userId);
            }
        }
        transferNode(source, targetParent, item.getFinalName(),
                ConflictStrategy.fromCode(item.getStrategy()), task, ctx);
    }

    /**
     * 递归传输单个节点，返回是否完整成功（含所有后代）。
     */
    private boolean transferNode(FileNode source, FileNode targetParent, String finalName,
                                 ConflictStrategy strategy, TransferTask task, TransferContext ctx) {
        if (ctx.checkCancelled()) {
            return false;
        }
        String userId = task.getUserId();
        String parentId = targetParent == null ? FileNodeConstants.ROOT_ID : targetParent.getId();
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentId, finalName);
        if (existing != null) {
            if (TYPE_FOLDER.equals(source.getType()) && TYPE_FOLDER.equals(existing.getType())) {
                // 文件夹与同名文件夹自动合并
                return transferFolderChildren(source, existing, strategy, task, ctx);
            }
            if (TYPE_FILE.equals(source.getType()) && TYPE_FOLDER.equals(existing.getType())) {
                ctx.recordFailure(source.getName(), "目标存在同名文件夹，不能覆盖");
                return false;
            }
            if (strategy == null) {
                ctx.recordFailure(source.getName(), "目标已存在同名文件或文件夹");
                return false;
            }
            switch (strategy) {
                case SKIP -> {
                    ctx.recordFailure(source.getName(), "跳过");
                    return false;
                }
                case KEEP -> finalName = FileConflictHelper.generateKeepName(fileMapper, userId, parentId, finalName);
                case OVERWRITE -> deleteTargetNode(existing, userId);
                default -> {
                }
            }
        }
        if (TYPE_FILE.equals(source.getType())) {
            return transferFile(source, targetParent, finalName, strategy, task, ctx);
        }
        FileNode newFolder = createTargetFolder(source, targetParent, finalName, task);
        boolean allOk = transferFolderChildren(source, newFolder, strategy, task, ctx);
        if (allOk && !ctx.checkCancelled() && OP_MOVE.equals(task.getOpType())) {
            deleteSourceNode(source, userId);
        }
        return allOk;
    }

    private boolean transferFolderChildren(FileNode sourceFolder, FileNode targetFolder,
                                           ConflictStrategy strategy, TransferTask task, TransferContext ctx) {
        boolean allOk = true;
        List<FileNode> children = fileMapper.selectByParentId(task.getUserId(), sourceFolder.getId());
        for (FileNode child : children) {
            if (ctx.checkCancelled()) {
                return false;
            }
            allOk &= transferNode(child, targetFolder, child.getName(), strategy, task, ctx);
        }
        return allOk;
    }

    /**
     * 传输单个文件：源端取流 → 目标端写入 → 建立目标节点 → 移动模式删除源。
     */
    private boolean transferFile(FileNode source, FileNode targetParent, String finalName,
                                 ConflictStrategy strategy, TransferTask task, TransferContext ctx) {
        String userId = task.getUserId();
        List<RLock> locks = acquireMountLocks(source, task, userId, ctx);
        if (locks == null) {
            return false;
        }
        try {
            boolean targetRemote = FileNodeConstants.SOURCE_REMOTE.equals(task.getTargetType());
            if (targetRemote) {
                transferToRemote(source, targetParent, finalName, strategy, task);
            } else {
                transferToLocal(source, targetParent, finalName, task);
            }
            // 移动模式先删除源，源删除成功才算该文件传输完成
            if (OP_MOVE.equals(task.getOpType())) {
                deleteSourceNode(source, userId);
            }
            ctx.recordSuccess();
            return true;
        } catch (BusinessException e) {
            ctx.recordFailure(source.getName(), e.getMessage());
            return false;
        } catch (Exception e) {
            ctx.recordFailure(source.getName(), "传输失败: " + e.getMessage());
            return false;
        } finally {
            for (int i = locks.size() - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
        }
    }

    /**
     * 本地/远程 → 远程：写远端并立即建立本地镜像节点。
     */
    private void transferToRemote(FileNode source, FileNode targetParent, String finalName,
                                  ConflictStrategy strategy, TransferTask task) throws IOException {
        String userId = task.getUserId();
        RemoteMount targetMount = remoteMountSupport.requireOwnedMount(task.getTargetMountId(), userId);
        RemoteProtocolAdapter adapter = adapterFactory.create(targetMount);
        String parentRemotePath = remoteMountSupport.deriveRemotePath(targetParent, targetMount);
        String remotePath = FilePathUtil.buildPathName(parentRemotePath, finalName);

        // 执行阶段复查远端真实状态（镜像可能过期）
        if (adapter.exists(remotePath)) {
            if (strategy == ConflictStrategy.OVERWRITE) {
                adapter.delete(remotePath);
            } else {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "远端已存在同名文件: " + finalName);
            }
        }

        long size = source.getSize() == null ? 0L : source.getSize();
        try (InputStream in = openSourceStream(source, task)) {
            adapter.upload(remotePath, in, size, source.getMimeType());
        }

        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(targetParent.getId());
        node.setName(finalName);
        node.setType(TYPE_FILE);
        node.setSize(size);
        node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
        node.setRemoteMountId(targetMount.getId());
        node.setPath(FilePathUtil.buildChildPath(targetParent));
        node.setMimeType(source.getMimeType());
        node.setStatus(1);
        fileMapper.insert(node);
    }

    /**
     * 本地/远程 → 本地：落盘、计算文件身份 hash、建立本地节点并累加配额。
     */
    private void transferToLocal(FileNode source, FileNode targetParent, String finalName,
                                 TransferTask task) throws IOException {
        String userId = task.getUserId();
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user);
        String parentNamePath = filePathSupport.resolveNamePath(targetParent, userId);
        String targetNamePath = FilePathUtil.buildPathName(parentNamePath, finalName);
        Path physical = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), targetNamePath);

        try (InputStream in = openSourceStream(source, task)) {
            Files.createDirectories(physical.getParent());
            Files.copy(in, physical, StandardCopyOption.REPLACE_EXISTING);
        }
        long actualSize = Files.size(physical);
        String hash = FileHashUtil.identityHash(physical);

        FileNode node = fileNodeSupport.buildFileNode(userId,
                targetParent == null ? FileNodeConstants.ROOT_ID : targetParent.getId(),
                finalName, actualSize, hash, space.getId(), source.getMimeType());
        node.setPath(targetParent == null ? FileNodeConstants.ROOT_ID : FilePathUtil.buildChildPath(targetParent));
        node.setLastModified(source.getLastModified());
        fileMapper.insert(node);

        userSpaceSupport.updateUsedSpace(user, space, actualSize);
    }

    /**
     * 打开源文件输入流：本地读物理文件，远程走协议适配器下载。
     */
    private InputStream openSourceStream(FileNode source, TransferTask task) throws IOException {
        String userId = task.getUserId();
        if (FileNodeConstants.SOURCE_REMOTE.equals(task.getSourceType())) {
            RemoteMount sourceMount = remoteMountSupport.requireOwnedMount(task.getSourceMountId(), userId);
            String remotePath = remoteMountSupport.deriveRemotePath(source, sourceMount);
            return adapterFactory.create(sourceMount).download(remotePath);
        }
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user);
        String namePath = filePathSupport.resolveNamePath(source, userId);
        Path physical = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), namePath);
        if (!Files.exists(physical)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "源物理文件不存在: " + source.getName());
        }
        return Files.newInputStream(physical);
    }

    /**
     * 创建目标文件夹节点：本地目标直接建节点，远程目标先建远端目录再建镜像。
     */
    private FileNode createTargetFolder(FileNode source, FileNode targetParent, String finalName,
                                        TransferTask task) {
        String userId = task.getUserId();
        String parentId = targetParent == null ? FileNodeConstants.ROOT_ID : targetParent.getId();
        if (FileNodeConstants.SOURCE_REMOTE.equals(task.getTargetType())) {
            RemoteMount targetMount = remoteMountSupport.requireOwnedMount(task.getTargetMountId(), userId);
            String parentRemotePath = remoteMountSupport.deriveRemotePath(targetParent, targetMount);
            String remotePath = FilePathUtil.buildPathName(parentRemotePath, finalName);
            RemoteProtocolAdapter adapter = adapterFactory.create(targetMount);
            if (!adapter.exists(remotePath)) {
                adapter.createFolder(remotePath);
            }
            FileNode folder = new FileNode();
            folder.setUserId(userId);
            folder.setParentId(parentId);
            folder.setName(finalName);
            folder.setType(TYPE_FOLDER);
            folder.setSize(0L);
            folder.setSourceType(FileNodeConstants.SOURCE_REMOTE);
            folder.setRemoteMountId(targetMount.getId());
            folder.setPath(FilePathUtil.buildChildPath(targetParent));
            folder.setStatus(1);
            fileMapper.insert(folder);
            return folder;
        }
        FileNode folder = fileNodeSupport.buildFolderNode(userId, parentId, finalName);
        folder.setPath(targetParent == null ? FileNodeConstants.ROOT_ID : FilePathUtil.buildChildPath(targetParent));
        folder.setLastModified(System.currentTimeMillis());
        fileMapper.insert(folder);
        return folder;
    }

    /**
     * 删除目标端已存在节点（覆盖）：本地物理删除，远程写回删除。
     */
    private void deleteTargetNode(FileNode existing, String userId) {
        if (FileNodeConstants.SOURCE_REMOTE.equals(existing.getSourceType())) {
            remoteFileOperationService.delete(existing, userId);
        } else {
            webDavFileOperationHelper.delete(userId, existing);
        }
    }

    /**
     * 移动模式下删除已传输成功的源节点：本地进回收站，远程真删。
     */
    private void deleteSourceNode(FileNode source, String userId) {
        try {
            if (FileNodeConstants.SOURCE_REMOTE.equals(source.getSourceType())) {
                remoteFileOperationService.delete(source, userId);
            } else {
                trashDeleteSupport.doDeleteToTrash(List.of(source.getId()), userId);
            }
        } catch (Exception e) {
            throw new SystemException(ResultCode.BUSINESS_ERROR, "删除源节点失败: " + source.getName(), e);
        }
    }

    /**
     * 按挂载 ID 排序获取本次文件传输涉及的挂载点锁（避免死锁），无远程参与返回空列表。
     *
     * @return 已持有的锁列表；源节点读取失败时为 {@code null}（已记录失败）
     */
    private List<RLock> acquireMountLocks(FileNode source, TransferTask task, String userId, TransferContext ctx) {
        List<String> mountIds = new ArrayList<>(2);
        if (FileNodeConstants.SOURCE_REMOTE.equals(task.getSourceType()) && task.getSourceMountId() != null) {
            mountIds.add(task.getSourceMountId());
        }
        if (FileNodeConstants.SOURCE_REMOTE.equals(task.getTargetType()) && task.getTargetMountId() != null
                && !mountIds.contains(task.getTargetMountId())) {
            mountIds.add(task.getTargetMountId());
        }
        mountIds.sort(Comparator.naturalOrder());
        List<RLock> locks = new ArrayList<>(mountIds.size());
        try {
            for (String mountId : mountIds) {
                RLock lock = remoteMountLock.getLock(mountId);
                if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR, "获取挂载点锁超时");
                }
                locks.add(lock);
            }
            return locks;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locks.forEach(RLock::unlock);
            ctx.recordFailure(source.getName(), "传输任务被中断");
            return null;
        } catch (BusinessException e) {
            locks.forEach(RLock::unlock);
            ctx.recordFailure(source.getName(), e.getMessage());
            return null;
        }
    }
}
