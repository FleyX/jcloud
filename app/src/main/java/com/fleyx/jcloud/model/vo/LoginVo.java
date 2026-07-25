package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 登录成功返回的视图对象。
 */
@Data
public class LoginVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * JWT Token。
     */
    private String token;

    /**
     * 当前用户信息。
     */
    private UserVo userInfo;

    /**
     * 当前用户拥有的全部资源编码（API 与 VIEW 资源）。
     */
    private List<String> resources;

    /**
     * 系统是否已完成初始化（仅管理员有意义）。
     */
    private Boolean initialized;
}
