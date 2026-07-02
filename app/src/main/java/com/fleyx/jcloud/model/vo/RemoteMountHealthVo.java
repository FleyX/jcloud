package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程挂载健康状态视图。
 */
@Data
public class RemoteMountHealthVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String status;
    private String message;
}
