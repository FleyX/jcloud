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
}
