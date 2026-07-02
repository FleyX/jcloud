package com.fleyx.jcloud.model.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 远程挂载详情视图，包含凭据等敏感配置，仅用于编辑场景。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RemoteMountDetailVo extends RemoteMountVo {

    @Serial
    private static final long serialVersionUID = 1L;

    private String url;
    private String username;
    private String password;
    private String rootPath;
}
