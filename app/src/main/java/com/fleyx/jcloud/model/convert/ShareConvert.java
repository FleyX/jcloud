package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.Share;
import com.fleyx.jcloud.model.vo.ShareVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 分享对象转换器。
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ShareConvert {

    /**
     * PO -> VO。
     */
    @Mapping(target = "hasPassword", expression = "java(po.getPasswordHash() != null && !po.getPasswordHash().isBlank())")
    ShareVo poToVo(Share po);

    /**
     * PO 列表 -> VO 列表。
     */
    List<ShareVo> poListToVoList(List<Share> poList);
}
