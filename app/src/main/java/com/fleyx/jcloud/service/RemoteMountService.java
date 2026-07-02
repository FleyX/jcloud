package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.RemoteMountPageQueryDto;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.RemoteMountUpdateDto;
import com.fleyx.jcloud.model.vo.RemoteMountDetailVo;
import com.fleyx.jcloud.model.vo.RemoteMountHealthVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;

import java.util.List;

/**
 * 远程挂载服务。
 */
public interface RemoteMountService {

    /**
     * 分页查询用户的远程挂载。
     *
     * @param dto    查询条件
     * @param userId 用户 ID
     * @return 分页结果
     */
    IPage<RemoteMountVo> page(RemoteMountPageQueryDto dto, String userId);

    /**
     * 创建远程挂载。
     *
     * @param dto    保存 DTO
     * @param userId 用户 ID
     * @return 创建后的挂载视图
     */
    RemoteMountVo save(RemoteMountSaveDto dto, String userId);

    /**
     * 更新远程挂载。
     *
     * @param dto    更新 DTO
     * @param userId 用户 ID
     * @return 更新后的挂载视图
     */
    RemoteMountVo update(RemoteMountUpdateDto dto, String userId);

    /**
     * 删除远程挂载（取消挂载）。
     *
     * @param id     挂载 ID
     * @param userId 用户 ID
     */
    void delete(String id, String userId);

    /**
     * 查询远程挂载详情。
     *
     * @param id     挂载 ID
     * @param userId 用户 ID
     * @return 详情视图
     */
    RemoteMountDetailVo detail(String id, String userId);

    /**
     * 测试现有远程挂载连接。
     *
     * @param id     挂载 ID
     * @param userId 用户 ID
     */
    void testConnection(String id, String userId);

    /**
     * 测试表单中的远程挂载连接。
     *
     * @param dto 保存 DTO
     */
    void testConnection(RemoteMountSaveDto dto);

    /**
     * 检查用户所有远程挂载的健康状态。
     *
     * @param userId 用户 ID
     * @return 健康状态列表
     */
    List<RemoteMountHealthVo> healthCheck(String userId);
}
