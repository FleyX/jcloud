package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.vo.FileNodeVo;

import java.util.List;

/**
 * 远程文件组织操作服务。
 */
public interface RemoteFileOperationService {

    /**
     * 重命名远程文件/文件夹。
     *
     * @param node    远程节点
     * @param newName 新名称
     * @param userId  用户 ID
     * @return 重命名后的节点视图
     */
    FileNodeVo rename(FileNode node, String newName, String userId);

    /**
     * 移动远程文件/文件夹到新的远程父目录（同一挂载点）。
     *
     * @param node           远程节点
     * @param newParentNode  新的远程父目录
     * @param finalName      最终名称（已处理冲突）
     * @param userId         用户 ID
     * @return 移动后的节点视图
     */
    FileNodeVo move(FileNode node, FileNode newParentNode, String finalName, String userId);

    /**
     * 复制远程文件/文件夹。
     *
     * @param node          远程节点
     * @param newParentNode 目标远程父目录
     * @param userId        用户 ID
     * @return 始终抛出业务异常
     */
    FileNodeVo copy(FileNode node, FileNode newParentNode, String userId);

    /**
     * 删除远程文件/文件夹。
     * <p>
     * 删除挂载点本身会调用 {@link RemoteMountService#delete(String, String)} 取消挂载。
     *
     * @param node   远程节点
     * @param userId 用户 ID
     */
    void delete(FileNode node, String userId);

    /**
     * 校验所有节点来源一致（全为本地或全为同一远程挂载点）。
     *
     * @param nodes 待校验节点
     */
    void validateSameSource(List<FileNode> nodes);

    /**
     * 根据本地 FileNode 推导完整的远程存储路径。
     *
     * @param node  远程文件节点
     * @param mount 所属挂载配置
     * @return 远程路径
     */
    String deriveRemotePath(FileNode node, RemoteMount mount);
}
