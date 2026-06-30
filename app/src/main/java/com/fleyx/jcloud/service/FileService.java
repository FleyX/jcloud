package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.vo.BatchUploadPreCheckItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件业务接口。
 */
public interface FileService {

    /**
     * 上传文件到指定父目录。
     *
     * @param file     上传文件
     * @param userId   用户 ID
     * @param parentId 目标父节点 ID
     * @param strategy 冲突解决策略，可选
     * @return 文件节点视图；跳过返回 {@code null}
     */
    FileNodeVo upload(MultipartFile file, String userId, String parentId, String strategy);

    /**
     * 分页查询用户的文件列表。
     *
     * @param dto    分页查询条件
     * @param userId 用户 ID
     * @return 分页结果
     */
    IPage<FileNodeVo> list(FilePageQueryDto dto, String userId);

    /**
     * 下载文件。
     *
     * @param fileId 文件节点 ID
     * @param userId 用户 ID
     * @return 文件下载结果
     */
    FileDownloadResult download(String fileId, String userId);

    /**
     * 查询指定父目录下的直接子文件夹。
     *
     * @param parentId 父节点 ID
     * @param userId   用户 ID
     * @return 子文件夹列表
     */
    List<FileNodeVo> listChildFolders(String parentId, String userId);

    /**
     * 批量上传前预检：检查目标父目录下是否存在同名节点，并查询可用于秒传的候选文件。
     *
     * @param items  预检参数列表
     * @param userId 用户 ID
     * @return 批量预检结果项列表
     */
    List<BatchUploadPreCheckItemVo> preCheckUpload(List<FileUploadPreCheckDto> items, String userId);

    /**
     * 秒传：复用候选文件的物理数据创建新的文件节点。
     *
     * @param dto    秒传参数
     * @param userId 用户 ID
     * @return 新文件节点视图
     */
    FileNodeVo instantUpload(FileInstantUploadDto dto, String userId);
}
