package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.util.RemotePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 远程挂载共享支撑组件。
 * <p>
 * 收敛远程域中重复出现的挂载归属校验、挂载点节点查询与远端路径推导逻辑。
 */
@Component
@RequiredArgsConstructor
public class RemoteMountSupport {

    private final RemoteMountMapper remoteMountMapper;
    private final FileMapper fileMapper;
    private final FilePathSupport filePathSupport;

    /**
     * 按 ID 查询挂载并校验归属（未逻辑删除）。
     *
     * @param id     挂载 ID
     * @param userId 用户 ID
     * @return 远程挂载
     */
    public RemoteMount requireOwnedMount(String id, String userId) {
        RemoteMount mount = remoteMountMapper.selectById(id);
        if (mount == null || !mount.getUserId().equals(userId) || mount.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "远程挂载不存在");
        }
        return mount;
    }

    /**
     * 查询挂载点对应的根级文件节点。
     *
     * @param remoteMountId 远程挂载 ID
     * @param userId        用户 ID
     * @return 挂载点节点，不存在返回 {@code null}
     */
    public FileNode findMountNode(String remoteMountId, String userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getRemoteMountId, remoteMountId);
        wrapper.eq(FileNode::getParentId, FileNodeConstants.ROOT_ID);
        wrapper.eq(FileNode::getSourceType, FileNodeConstants.SOURCE_REMOTE);
        return fileMapper.selectOne(wrapper);
    }

    /**
     * 判断节点是否为远程挂载点（根级远程节点）。
     *
     * @param node 文件节点
     * @return 是否挂载点
     */
    public boolean isMountPoint(FileNode node) {
        return FileNodeConstants.ROOT_ID.equals(node.getParentId())
                && FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType());
    }

    /**
     * 推导节点在远端的相对路径（相对挂载点）。
     *
     * @param node  文件节点
     * @param mount 远程挂载
     * @return 远端相对路径
     */
    public String deriveRemotePath(FileNode node, RemoteMount mount) {
        String fullNamePath = filePathSupport.resolveNamePath(node, node.getUserId());
        return RemotePathUtil.relativeNamePath(mount.getName(), fullNamePath);
    }
}
