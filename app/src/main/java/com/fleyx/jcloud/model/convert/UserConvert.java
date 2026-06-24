package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.UserProfileVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 用户对象转换器。
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserConvert {

    /**
     * DTO -> PO。
     */
    User dtoToPo(UserSaveDto dto);

    /**
     * PO -> VO。
     */
    @Mapping(target = "isAdmin", ignore = true)
    UserVo poToVo(User po);

    /**
     * PO 列表 -> VO 列表。
     */
    List<UserVo> poListToVoList(List<User> poList);

    /**
     * PO -> 个人信息 VO。
     */
    UserProfileVo poToProfileVo(User po);
}
