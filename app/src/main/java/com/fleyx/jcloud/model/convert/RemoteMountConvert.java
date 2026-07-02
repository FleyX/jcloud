package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.RemoteMountUpdateDto;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.vo.RemoteMountDetailVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

/**
 * 远程挂载对象转换器。
 */
@Mapper(componentModel = "spring")
public interface RemoteMountConvert {

    RemoteMount saveDtoToPo(RemoteMountSaveDto dto);

    RemoteMount updateDtoToPo(RemoteMountUpdateDto dto);

    void updateDtoToPo(RemoteMountUpdateDto dto, @MappingTarget RemoteMount po);

    RemoteMountVo poToVo(RemoteMount po);

    RemoteMountDetailVo poToDetailVo(RemoteMount po);
}
