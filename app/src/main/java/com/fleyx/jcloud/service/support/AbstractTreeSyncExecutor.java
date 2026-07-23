package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 目录树同步骨架（模板方法）。
 * <p>
 * 统一远程挂载同步与用户存储空间同步的目录对齐流程：
 * 列举同步源子项 → 删除同步源中不存在的 DB 节点 → 逐条对齐同步源条目
 * （新增/类型替换/元数据更新/文件夹递归）。
 * 差异点（子项列举、元数据比较与更新、节点构造、子树删除副作用）由子类实现。
 *
 * @param <S> 同步源类型（如远端目录标识、物理目录）
 * @param <E> 同步条目类型
 */
public abstract class AbstractTreeSyncExecutor<S, E> {

    protected final FileMapper fileMapper;

    protected AbstractTreeSyncExecutor(FileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    /**
     * 同步一个文件夹，对齐 DB 子树与同步源子项。
     *
     * @param parentNode 父节点
     * @param source     同步源
     * @param context    同步上下文
     * @throws Exception 子项列举失败时向上传播（整个任务失败）
     */
    protected void syncFolder(FileNode parentNode, S source, SyncContext context) throws Exception {
        List<E> sourceChildren = listChildren(source, context);
        if (sourceChildren == null) {
            return;
        }

        List<FileNode> dbChildren = fileMapper.selectByParentId(context.getUserId(), parentNode.getId());
        Map<String, FileNode> dbByName = new HashMap<>();
        for (FileNode child : dbChildren) {
            dbByName.put(child.getName(), child);
        }
        Map<String, E> sourceByName = new HashMap<>();
        for (E entry : sourceChildren) {
            sourceByName.put(nameOf(entry), entry);
        }

        for (FileNode dbChild : dbChildren) {
            if (!sourceByName.containsKey(dbChild.getName())) {
                try {
                    deleteDbSubtree(dbChild, context);
                    context.recordSuccess();
                } catch (Exception e) {
                    recordDeleteFailure(dbChild, e, context);
                }
            }
        }

        for (E entry : sourceChildren) {
            try {
                processEntry(parentNode, dbByName.get(nameOf(entry)), entry, source, context);
            } catch (Exception e) {
                recordEntryFailure(entry, e, context);
            }
        }
    }

    private void processEntry(FileNode parentNode, FileNode dbChild, E entry, S source,
                              SyncContext context) throws Exception {
        boolean folder = isFolder(entry);
        if (dbChild == null || folder != isDbFolder(dbChild)) {
            if (dbChild != null) {
                deleteDbSubtree(dbChild, context);
            }
            FileNode newNode = createNode(parentNode, entry, context);
            fileMapper.insert(newNode);
            context.recordSuccess();
            if (folder) {
                syncFolder(newNode, childSource(entry, source), context);
            }
            return;
        }
        if (metaChanged(dbChild, entry)) {
            applyMetaUpdate(dbChild, entry);
        }
        context.recordSuccess();
        if (folder) {
            syncFolder(dbChild, childSource(entry, source), context);
        }
    }

    private boolean isDbFolder(FileNode node) {
        return FileNodeConstants.TYPE_FOLDER.equals(node.getType());
    }

    /**
     * 列举同步源子项。
     *
     * @return 子项列表；返回 {@code null} 表示读取失败（错误已记录），跳过该目录
     */
    protected abstract List<E> listChildren(S source, SyncContext context) throws Exception;

    /**
     * 条目是否为文件夹。
     */
    protected abstract boolean isFolder(E entry);

    /**
     * 条目名称。
     */
    protected abstract String nameOf(E entry);

    /**
     * 判断 DB 节点元数据是否与同步条目不一致。
     */
    protected abstract boolean metaChanged(FileNode dbNode, E entry) throws Exception;

    /**
     * 应用元数据更新（含缓存清理等副作用与持久化）。
     */
    protected abstract void applyMetaUpdate(FileNode dbNode, E entry) throws Exception;

    /**
     * 构造新节点（不持久化）。
     */
    protected abstract FileNode createNode(FileNode parentNode, E entry, SyncContext context) throws Exception;

    /**
     * 计算条目对应的子级同步源。
     */
    protected abstract S childSource(E entry, S source);

    /**
     * 删除 DB 子树（含物理数据/缓存清理等副作用）。
     */
    protected abstract void deleteDbSubtree(FileNode node, SyncContext context);

    /**
     * 记录子树删除失败，子类可覆盖以调整消息或补充日志。
     */
    protected void recordDeleteFailure(FileNode dbChild, Exception e, SyncContext context) {
        context.recordFailure("删除节点 " + dbChild.getName() + " 失败: " + e.getMessage());
    }

    /**
     * 记录条目同步失败，子类可覆盖以调整消息或补充日志。
     */
    protected void recordEntryFailure(E entry, Exception e, SyncContext context) {
        context.recordFailure("同步 " + nameOf(entry) + " 失败: " + e.getMessage());
    }
}
