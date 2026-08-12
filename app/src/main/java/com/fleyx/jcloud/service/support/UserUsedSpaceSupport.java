package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用户已用空间集中记账支撑组件。
 * <p>
 * 本组件是全项目唯一允许写 {@code t_user.used_space} 的入口（逻辑口径，见 ADR-0027）。
 * 后续所有写路径（上传、删除、回收站、恢复等）统一经此记账，避免各处散落的
 * 读-改-写更新互相覆盖。
 */
@Component
@RequiredArgsConstructor
public class UserUsedSpaceSupport {

    private final UserMapper userMapper;

    /**
     * 按增量原子累加用户已用空间（单语句 UPDATE，并发调用不丢更新，结果不小于 0）。
     *
     * @param userId 用户 ID
     * @param delta  容量增量（可为负）
     */
    public void addUsedSpace(String userId, long delta) {
        userMapper.addUsedSpace(userId, delta);
    }

    /**
     * 按逻辑口径全量重算用户已用空间并落库，返回重算后的最新值。
     * <p>
     * 口径：该用户全部本地文件节点（type=file、source_type=local）size 之和
     * + 回收站记录 total_size 之和；远程节点与文件夹节点不计入（回收站保留期内继续占用）。
     *
     * @param userId 用户 ID
     * @return 重算后的已用空间（用户不存在时为 0）
     */
    public long recalcUsedSpace(String userId) {
        userMapper.recalcUsedSpace(userId);
        User user = userMapper.selectById(userId);
        return user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
    }
}
