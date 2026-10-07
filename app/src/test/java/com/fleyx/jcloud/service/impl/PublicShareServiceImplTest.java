package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.ShareItemMapper;
import com.fleyx.jcloud.mapper.ShareMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.Share;
import com.fleyx.jcloud.model.po.ShareItem;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.PublicShareVo;
import com.fleyx.jcloud.service.FileDownloadService;
import com.fleyx.jcloud.service.FilePreviewService;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.support.AuthRateLimitSupport;
import com.fleyx.jcloud.util.ShareAccessChecker;
import com.fleyx.jcloud.util.ShareTokenUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 公开分享业务实现单元测试：顶层分享项按字典序倒序排序，folder 恒在 file 之前。
 */
@ExtendWith(MockitoExtension.class)
class PublicShareServiceImplTest {

    @Mock
    private ShareMapper shareMapper;

    @Mock
    private ShareItemMapper shareItemMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private FileConvert fileConvert;

    @Mock
    private ShareTokenUtil shareTokenUtil;

    @Mock
    private ShareAccessChecker accessChecker;

    @Mock
    private FileService fileService;

    @Mock
    private FilePreviewService filePreviewService;

    @Mock
    private FileDownloadService fileDownloadService;

    @Mock
    private AuthRateLimitSupport authRateLimitSupport;

    @InjectMocks
    private PublicShareServiceImpl publicShareService;

    /**
     * 顶层分享项：查询结果无序时，folder 排在全部 file 之前，file 之间保持原顺序。
     */
    @Test
    void shouldSortTopItemsFolderFirst() {
        Share share = activeShare();
        when(shareMapper.selectByShareCode("c1")).thenReturn(share);
        when(shareItemMapper.selectByShareId(share.getId()))
                .thenReturn(List.of(item("f1"), item("f2"), item("d1")));
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(
                fileNode("f1", "file"), fileNode("f2", "file"), fileNode("d1", "folder")));
        when(fileConvert.poToVo(any(FileNode.class))).thenAnswer(invocation -> {
            FileNode node = invocation.getArgument(0);
            FileNodeVo vo = new FileNodeVo();
            vo.setId(node.getId());
            vo.setType(node.getType());
            return vo;
        });

        PublicShareVo vo = publicShareService.getShare("c1");

        assertEquals(List.of("d1", "f1", "f2"), vo.getItems().stream().map(FileNodeVo::getId).toList());
    }

    private Share activeShare() {
        Share share = new Share();
        share.setId("share-1");
        share.setUserId("user-1");
        share.setName("测试分享");
        share.setViewCount(0L);
        share.setStatus(CommonStatus.ENABLED.getCode());
        share.setDeleteAt(0L);
        return share;
    }

    private ShareItem item(String fileNodeId) {
        ShareItem item = new ShareItem();
        item.setShareId("share-1");
        item.setFileNodeId(fileNodeId);
        return item;
    }

    private FileNode fileNode(String id, String type) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setType(type);
        return node;
    }
}
