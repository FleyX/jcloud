package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaSubtitleMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaSubtitle;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.support.MediaPlaybackResolveSupport.Playable;
import com.fleyx.jcloud.util.MediaSubtitleNameParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 位图字幕烧录装配支撑组件（工单 02/04）：转码会话创建前的烧录参数校验与装配。
 * <p>
 * 内嵌轨校验序号存在且为位图轨；外挂位图字幕校验归属/格式/节点归属后，
 * 本地装物理路径、远程装下载流（idx 成对需同目录同主名 .sub）。
 */
@Component
@RequiredArgsConstructor
public class MediaBurnInSubtitleSupport {

    private final MediaSubtitleMapper mediaSubtitleMapper;
    private final FileMapper fileMapper;
    private final RemoteFileService remoteFileService;

    /**
     * 外挂位图字幕装配结果：本地外挂物理路径与远程外挂流二选一（校验保证互斥）。
     */
    public record ExternalSubtitleBurn(Path path, TranscodeCommandBuilder.ExternalSubtitleStream stream) {
    }

    /**
     * 校验烧录字幕轨序号：必须存在且为非文本（位图）轨，文本轨走 WebVTT 链路不接受烧录。
     * probe 失败回退为空轨列表，任何序号都命中「字幕轨不存在」。
     */
    public void validateSubtitleIndex(MediaProbeResult probe, Integer subtitleIndex) {
        if (subtitleIndex == null) {
            return;
        }
        for (MediaProbeResult.Track track : probe.subtitleTracks()) {
            if (track.index() == subtitleIndex) {
                if (MediaProbeSupport.isTextSubtitle(track.codec())) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "文本字幕无需烧录");
                }
                return;
            }
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "字幕轨不存在");
    }

    /**
     * 校验并装配外挂位图字幕：记录存在且归属当前文件明细行、格式为位图（sup/idx）、
     * 字幕文件节点存在且属当前用户（同 extractExternalSubtitle 语义）；本地装物理路径（idx 的 .sub
     * 同物理目录由 ffmpeg 自动读取），远程装下载流（idx 需同目录同主名 .sub 成对，缺 .sub 拒绝）。
     *
     * @param playable           播放解析结果
     * @param externalSubtitleId 外部字幕记录 ID，null 表示不烧录外挂
     * @param userId             用户 ID
     * @param localPathResolver  本地物理路径解析（由调用方提供）
     * @return 装配结果（不烧录外挂时两字段均为 null）
     */
    public ExternalSubtitleBurn resolveExternalSubtitleBurn(Playable playable, String externalSubtitleId,
                                                            String userId, Function<FileNode, Path> localPathResolver) {
        if (externalSubtitleId == null) {
            return new ExternalSubtitleBurn(null, null);
        }
        MediaSubtitle subtitle = mediaSubtitleMapper.selectById(externalSubtitleId);
        if (subtitle == null || !playable.fileRowId().equals(subtitle.getFileId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "字幕不存在");
        }
        if (!MediaSubtitleNameParser.isBitmapFormat(subtitle.getFormat())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文本字幕无需烧录");
        }
        FileNode subNode = fileMapper.selectById(subtitle.getFileNodeId());
        if (subNode == null || !userId.equals(subNode.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "字幕文件不存在");
        }
        if (FileNodeConstants.SOURCE_REMOTE.equals(subNode.getSourceType())) {
            String mainName = MediaSubtitleNameParser.mainNameOf(subNode.getName());
            Supplier<InputStream> stream = () -> remoteFileService.download(subNode, userId).getInputStream();
            Supplier<InputStream> subStream = null;
            if ("idx".equals(subtitle.getFormat())) {
                FileNode subFileNode = fileMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getParentId, subNode.getParentId())
                        .eq(FileNode::getName, mainName + ".sub"));
                if (subFileNode == null) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR, "VobSub 字幕缺少 .sub 文件");
                }
                subStream = () -> remoteFileService.download(subFileNode, userId).getInputStream();
            }
            return new ExternalSubtitleBurn(null,
                    new TranscodeCommandBuilder.ExternalSubtitleStream(mainName, subtitle.getFormat(), stream, subStream));
        }
        return new ExternalSubtitleBurn(localPathResolver.apply(subNode), null);
    }
}
