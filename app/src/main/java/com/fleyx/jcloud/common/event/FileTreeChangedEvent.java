package com.fleyx.jcloud.common.event;

import com.fleyx.jcloud.common.enums.FileChangeOperation;
import org.springframework.context.ApplicationEvent;

/**
 * 文件树变更事件（ADR 0025）。
 * <p>
 * 所有改变文件树的写操作在事务提交后发布（after-commit，回滚不发布）。
 * 事件必须自包含：消费方在防抖 10~30 秒后才处理，届时节点可能已被物理删除，
 * 因此新旧父链信息（parentId + 物化路径 path）必须随事件携带，消费方不得假设能回查节点本身。
 * <p>
 * 双侧填充约定：MOVE/RESTORE 填新旧两侧；DELETE 只有旧侧；CREATE 只有新侧；
 * UPDATE（重命名）新旧同父、path 不变；PERMANENT_DELETE 无父链信息。
 */
public class FileTreeChangedEvent extends ApplicationEvent {

    /**
     * 操作类型。
     */
    private final FileChangeOperation operation;

    /**
     * 用户 ID。
     */
    private final String userId;

    /**
     * 节点 ID。
     */
    private final String nodeId;

    /**
     * 节点类型：file / folder（复用 FileNode.type）。
     */
    private final String nodeType;

    /**
     * 节点名称。
     */
    private final String name;

    /**
     * 文件大小（字节）；文件夹为 0。
     */
    private final Long size;

    /**
     * 变更前父节点 ID，可空。
     */
    private final String oldParentId;

    /**
     * 变更后父节点 ID，可空。
     */
    private final String newParentId;

    /**
     * 变更前物化路径（FileNode.path），可空。
     */
    private final String oldPath;

    /**
     * 变更后物化路径（FileNode.path），可空。
     */
    private final String newPath;

    /**
     * 创建事件。
     *
     * @param source      事件源
     * @param operation   操作类型
     * @param userId      用户 ID
     * @param nodeId      节点 ID
     * @param nodeType    节点类型
     * @param name        节点名称
     * @param size        文件大小，文件夹为 0
     * @param oldParentId 变更前父节点 ID，可空
     * @param newParentId 变更后父节点 ID，可空
     * @param oldPath     变更前物化路径，可空
     * @param newPath     变更后物化路径，可空
     */
    public FileTreeChangedEvent(Object source, FileChangeOperation operation, String userId, String nodeId,
                                String nodeType, String name, Long size,
                                String oldParentId, String newParentId, String oldPath, String newPath) {
        super(source);
        this.operation = operation;
        this.userId = userId;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.name = name;
        this.size = size;
        this.oldParentId = oldParentId;
        this.newParentId = newParentId;
        this.oldPath = oldPath;
        this.newPath = newPath;
    }

    /**
     * 获取操作类型。
     *
     * @return 操作类型
     */
    public FileChangeOperation getOperation() {
        return operation;
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 获取节点 ID。
     *
     * @return 节点 ID
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取节点类型。
     *
     * @return 节点类型
     */
    public String getNodeType() {
        return nodeType;
    }

    /**
     * 获取节点名称。
     *
     * @return 节点名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取文件大小。
     *
     * @return 文件大小
     */
    public Long getSize() {
        return size;
    }

    /**
     * 获取变更前父节点 ID。
     *
     * @return 变更前父节点 ID
     */
    public String getOldParentId() {
        return oldParentId;
    }

    /**
     * 获取变更后父节点 ID。
     *
     * @return 变更后父节点 ID
     */
    public String getNewParentId() {
        return newParentId;
    }

    /**
     * 获取变更前物化路径。
     *
     * @return 变更前物化路径
     */
    public String getOldPath() {
        return oldPath;
    }

    /**
     * 获取变更后物化路径。
     *
     * @return 变更后物化路径
     */
    public String getNewPath() {
        return newPath;
    }
}
