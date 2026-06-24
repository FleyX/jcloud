package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.dto.PermissionSaveDto;
import com.fleyx.jcloud.model.dto.PermissionUpdateDto;
import com.fleyx.jcloud.model.po.Permission;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.PermissionVo;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PermissionConvert {

    Permission saveDtoToPo(PermissionSaveDto dto);

    Permission updateDtoToPo(PermissionUpdateDto dto);

    PermissionVo poToVo(Permission po);

    PermissionTreeVo poToTreeVo(Permission po);

    List<PermissionTreeVo> poListToTreeVoList(List<Permission> poList);
}
