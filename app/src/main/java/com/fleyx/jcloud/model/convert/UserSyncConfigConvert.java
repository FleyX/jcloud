package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.UserSyncConfig;
import com.fleyx.jcloud.model.vo.UserSyncConfigVo;
import org.mapstruct.Mapper;

/**
 * 用户存储空间同步配置对象转换器。
 */
@Mapper(componentModel = "spring")
public interface UserSyncConfigConvert {

    /**
     * PO 转 VO。
     *
     * @param po 同步配置实体
     * @return 同步配置视图
     */
    UserSyncConfigVo poToVo(UserSyncConfig po);
}
