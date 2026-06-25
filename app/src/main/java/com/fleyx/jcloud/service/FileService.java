package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FilePreCheckDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件业务接口。
 */
public interface FileService {

    /**
     * 上传文件到用户根目录。
     *
     * @param file   上传文件
     * @param userId 用户 ID
     * @return 文件节点视图
     */
    FileNodeVo upload(MultipartFile file, Long userId);

    /**
     * 分页查询用户的文件列表。
     *
     * @param dto    分页查询条件
     * @param userId 用户 ID
     * @return 分页结果
     */
    IPage<FileNodeVo> list(FilePageQueryDto dto, Long userId);

    /**
     * 下载文件。
     *
     * @param fileId 文件节点 ID
     * @param userId 用户 ID
     * @return 文件下载结果
     */
    FileDownloadResult download(Long fileId, Long userId);

    /**
     * 秒传预检查：返回当前用户下与 partialHash 匹配的文件候选列表。
     *
     * @param dto    预检查参数
     * @param userId 用户 ID
     * @return 候选文件列表
     */
    List<FileNodeVo> preCheck(FilePreCheckDto dto, Long userId);

    /**
     * 秒传：复用候选文件的物理数据创建新的文件节点。
     *
     * @param dto    秒传参数
     * @param userId 用户 ID
     * @return 新文件节点视图
     */
    FileNodeVo instantUpload(FileInstantUploadDto dto, Long userId);
}
