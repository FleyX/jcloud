package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.vo.RoleVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RoleConvert {

    Role saveDtoToPo(RoleSaveDto dto);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "deleteAt", ignore = true)
    void updatePoFromDto(RoleUpdateDto dto, @MappingTarget Role po);

    RoleVo poToVo(Role po);

    List<RoleVo> poListToVoList(List<Role> poList);
}
