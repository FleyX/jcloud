package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户数据访问层。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名模糊查询用户。
     *
     * @param username 用户名关键字
     * @return 用户列表
     */
    List<User> selectByUsernameLike(@Param("username") String username);

    /**
     * 统计用户名数量（包含已逻辑删除的用户），用于全局唯一校验。
     *
     * @param username 用户名
     * @return 数量
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE username = #{username}")
    long countByUsernameIncludingDeleted(@Param("username") String username);

    /**
     * 按增量原子累加用户已用空间（单语句 UPDATE，结果不小于 0）。
     *
     * @param userId 用户 ID
     * @param delta  容量增量（可为负）
     * @return 影响行数
     */
    int addUsedSpace(@Param("userId") String userId, @Param("delta") long delta);

    /**
     * 按逻辑口径全量重算用户已用空间并落库。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int recalcUsedSpace(@Param("userId") String userId);

    /**
     * 查询逻辑删除超过保留期的用户（自定义 SQL 绕开 {@code @TableLogic} 自动过滤）。
     * <p>
     * 用于删除用户级联清理任务：delete_at 大于 0 表示已逻辑删除，
     * 小于 cutoff（毫秒时间戳）表示已超过保留期。
     *
     * @param cutoff 保留期截止毫秒时间戳
     * @return 过期逻辑删除用户列表
     */
    List<User> selectExpiredDeletedUsers(@Param("cutoff") long cutoff);
}
