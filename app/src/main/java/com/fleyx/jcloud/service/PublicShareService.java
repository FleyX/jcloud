package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.PublicShareVo;

import java.util.List;

/**
 * 公开分享业务接口。
 */
public interface PublicShareService {

    /**
     * 获取公开分享基础信息。
     *
     * @param shareCode 分享短码
     * @return 公开分享视图
     */
    PublicShareVo getShare(String shareCode);

    /**
     * 校验分享访问密码并返回访问凭证。
     *
     * @param shareCode 分享短码
     * @param password  访问密码
     * @return 访问 Token
     */
    String validateAccess(String shareCode, String password);

    /**
     * 获取分享项列表。
     *
     * @param shareCode  分享短码
     * @param parentId   父文件夹 ID，为空表示顶层
     * @param accessToken 访问凭证
     * @return 文件节点列表
     */
    List<FileNodeVo> listItems(String shareCode, String parentId, String accessToken);

    /**
     * 公开下载单个文件。
     *
     * @param shareCode   分享短码
     * @param fileNodeId  文件节点 ID
     * @param accessToken 访问凭证
     * @return 文件下载结果
     */
    FileDownloadResult downloadFile(String shareCode, String fileNodeId, String accessToken);

    /**
     * 公开预览文件。
     *
     * @param shareCode   分享短码
     * @param fileNodeId  文件节点 ID
     * @param type        预览类型
     * @param accessToken 访问凭证
     * @return 预览结果
     */
    PreviewResult previewFile(String shareCode, String fileNodeId, PreviewType type, String accessToken);

    /**
     * 公开批量下载。
     *
     * @param shareCode   分享短码
     * @param dto         批量下载请求
     * @param accessToken 访问凭证
     * @return 下载结果
     */
    BatchDownloadResult downloadBatch(String shareCode, FileBatchDownloadDto dto, String accessToken);

    /**
     * 查询批量下载任务状态。
     *
     * @param shareCode   分享短码
     * @param taskId      任务 ID
     * @param accessToken 访问凭证
     * @return 任务对象
     */
    FileZipTask getBatchTask(String shareCode, String taskId, String accessToken);

    /**
     * 下载已完成的批量下载 ZIP。
     *
     * @param shareCode   分享短码
     * @param taskId      任务 ID
     * @param accessToken 访问凭证
     * @return 文件下载结果
     */
    FileDownloadResult downloadBatchResult(String shareCode, String taskId, String accessToken);
}
