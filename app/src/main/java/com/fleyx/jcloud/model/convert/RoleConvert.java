package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.vo.RoleVo;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 角色对象转换器。
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RoleConvert {

    /**
     * PO -> VO。
     */
    RoleVo poToVo(Role po);

    /**
     * PO 列表 -> VO 列表。
     */
    List<RoleVo> poListToVoList(List<Role> poList);
}
