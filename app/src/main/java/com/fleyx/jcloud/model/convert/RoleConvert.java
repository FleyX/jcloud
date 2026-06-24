package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.vo.RoleVo;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RoleConvert {

    Role saveDtoToPo(RoleSaveDto dto);

    Role updateDtoToPo(RoleUpdateDto dto);

    RoleVo poToVo(Role po);

    List<RoleVo> poListToVoList(List<Role> poList);
}
