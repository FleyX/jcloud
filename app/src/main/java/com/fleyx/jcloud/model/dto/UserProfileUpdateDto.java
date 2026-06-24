package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 更新当前登录用户个人信息 DTO。
 */
@Data
public class UserProfileUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 邮箱。
     */
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 昵称。
     */
    @Size(max = 64, message = "昵称长度不能超过64位")
    private String nickname;
}
