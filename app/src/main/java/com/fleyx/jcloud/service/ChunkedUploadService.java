package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.ChunkedUploadCompleteDto;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.vo.BatchChunkedUploadInitItemVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadInitVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 分片上传服务。
 */
public interface ChunkedUploadService {

    /**
     * 批量初始化分片上传任务。
     *
     * @param userId 用户 ID
     * @param items  初始化参数列表
     * @return 批量初始化结果项列表
     */
    List<BatchChunkedUploadInitItemVo> init(String userId, List<ChunkedUploadInitDto> items);

    /**
     * 上传单个分片并校验分片 hash。
     *
     * @param userId    用户 ID
     * @param uploadId  上传任务 ID
     * @param chunkIndex 分片索引
     * @param chunk     分片文件
     * @param chunkHash 分片 hash
     * @return 分片上传结果
     */
    ChunkedUploadChunkVo uploadChunk(String userId, String uploadId, Integer chunkIndex,
                                     MultipartFile chunk, String chunkHash);

    /**
     * 查询已上传的分片索引列表。
     *
     * @param userId   用户 ID
     * @param uploadId 上传任务 ID
     * @return 已上传分片索引列表
     */
    List<Integer> listUploadedChunks(String userId, String uploadId);

    /**
     * 完成分片上传，合并分片并创建文件节点。
     *
     * @param userId   用户 ID
     * @param uploadId 上传任务 ID
     * @param dto      完成参数，包含冲突解决策略
     * @return 创建的文件节点视图；跳过返回 {@code null}
     */
    FileNodeVo complete(String userId, String uploadId, ChunkedUploadCompleteDto dto);
}
