package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.SystemInitDto;
import com.fleyx.jcloud.model.vo.SystemInitStatusVo;

/**
 * 系统初始化业务接口。
 */
public interface SystemInitService {

    /**
     * 查询系统初始化状态。
     *
     * @return 初始化状态视图
     */
    SystemInitStatusVo getInitStatus();

    /**
     * 执行系统初始化。
     *
     * @param dto 初始化数据
     */
    void initialize(SystemInitDto dto);

    /**
     * 判断系统是否已完成初始化。
     *
     * @return true 表示已初始化
     */
    boolean isInitialized();
}
