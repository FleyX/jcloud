package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;

import java.util.List;

/**
 * 媒体库管理服务。
 */
public interface MediaDirectoryService {

    /**
     * 查询当前用户的媒体库列表（含来源目录）。
     *
     * @param userId 用户 ID
     * @return 媒体库列表
     */
    List<MediaDirectoryVo> list(String userId);

    /**
     * 新增媒体库并触发首次扫描。
     *
     * @param dto    入参
     * @param userId 用户 ID
     * @return 媒体库视图
     */
    MediaDirectoryVo save(MediaDirectorySaveDto dto, String userId);

    /**
     * 更新媒体库。媒体类型创建后不可修改；增删来源目录会中断当前任务并强制全量重扫。
     *
     * @param dto    入参
     * @param userId 用户 ID
     * @return 媒体库视图
     */
    MediaDirectoryVo update(MediaDirectoryUpdateDto dto, String userId);

    /**
     * 删除媒体库及其来源目录、条目（不删除文件）。
     *
     * @param id     媒体库 ID
     * @param userId 用户 ID
     */
    void delete(String id, String userId);
}
