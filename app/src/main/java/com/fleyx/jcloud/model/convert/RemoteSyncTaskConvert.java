package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.model.vo.RemoteSyncTaskVo;
import org.mapstruct.Mapper;

/**
 * 远程同步任务对象转换器。
 */
@Mapper(componentModel = "spring")
public interface RemoteSyncTaskConvert {

    RemoteSyncTaskVo poToVo(RemoteSyncTask po);
}
