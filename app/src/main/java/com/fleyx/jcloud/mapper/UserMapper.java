package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
