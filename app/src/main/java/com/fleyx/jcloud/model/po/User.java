package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户表实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户名。
     */
    private String username;

    /**
     * 密码。
     */
    private String password;

    /**
     * 邮箱。
     */
    private String email;

    /**
     * 昵称。
     */
    private String nickname;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;

    /**
     * 是否为超级管理员：1 是，0 否。
     */
    @TableField("is_admin")
    private Integer isAdmin;

    /**
     * 判断当前用户是否为内置超级管理员。
     */
    public boolean isSuperAdmin() {
        return Integer.valueOf(1).equals(this.isAdmin);
    }
}
