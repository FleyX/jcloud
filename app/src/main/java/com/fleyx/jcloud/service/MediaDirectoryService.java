package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;

import java.util.List;

/**
 * 视频目录管理服务。
 */
public interface MediaDirectoryService {

    /**
     * 查询当前用户的视频目录列表。
     *
     * @param userId 用户 ID
     * @return 目录列表
     */
    List<MediaDirectoryVo> list(String userId);

    /**
     * 新增视频目录并触发首次扫描。
     *
     * @param dto    入参
     * @param userId 用户 ID
     * @return 目录视图
     */
    MediaDirectoryVo save(MediaDirectorySaveDto dto, String userId);

    /**
     * 更新视频目录。
     *
     * @param dto    入参
     * @param userId 用户 ID
     * @return 目录视图
     */
    MediaDirectoryVo update(MediaDirectoryUpdateDto dto, String userId);

    /**
     * 删除视频目录及其条目（不删除文件）。
     *
     * @param id     目录 ID
     * @param userId 用户 ID
     */
    void delete(String id, String userId);
}
