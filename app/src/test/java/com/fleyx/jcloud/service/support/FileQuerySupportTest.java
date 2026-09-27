package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.RemoteFileService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件查询共享支撑组件测试。
 */
@ExtendWith(MockitoExtension.class)
class FileQuerySupportTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private StorageSpaceMapper storageSpaceMapper;

    @Mock
    private FileConvert fileConvert;

    @Mock
    private RemoteFileService remoteFileService;

    @Mock
    private UserSpaceSupport userSpaceSupport;

    @Mock
    private FileNodeSupport fileNodeSupport;

    @Mock
    private FilePathSupport filePathSupport;

    @InjectMocks
    private FileQuerySupport fileQuerySupport;

    @BeforeEach
    void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FileNode.class);
    }

    @Test
    void shouldReturnAllNodesForValidIds() {
        FileNode first = node("n1", "u1");
        FileNode second = node("n2", "u1");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(fileConvert.poToVo(first)).thenReturn(vo("n1"));
        when(fileConvert.poToVo(second)).thenReturn(vo("n2"));

        List<FileNodeVo> result = fileQuerySupport.listByIds("u1", List.of("n1", "n2"));

        assertEquals(List.of("n1", "n2"), result.stream().map(FileNodeVo::getId).toList());
    }

    @Test
    void shouldSkipMissingIds() {
        FileNode existing = node("n1", "u1");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(existing));
        when(fileConvert.poToVo(existing)).thenReturn(vo("n1"));

        List<FileNodeVo> result = fileQuerySupport.listByIds("u1", List.of("n1", "n2"));

        assertEquals(List.of("n1"), result.stream().map(FileNodeVo::getId).toList());
    }

    @Test
    void shouldFilterNodesOwnedByOtherUsers() {
        FileNode own = node("n1", "u1");
        FileNode foreign = node("n2", "u2");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(own, foreign));
        when(fileConvert.poToVo(own)).thenReturn(vo("n1"));

        List<FileNodeVo> result = fileQuerySupport.listByIds("u1", List.of("n1", "n2"));

        assertEquals(List.of("n1"), result.stream().map(FileNodeVo::getId).toList());
    }

    @Test
    void shouldReturnEmptyWithoutQueryWhenIdsEmpty() {
        assertTrue(fileQuerySupport.listByIds("u1", List.of()).isEmpty());
        verifyNoInteractions(fileMapper);
    }

    @Test
    void shouldAlwaysPlaceFoldersFirstInSearchResults() {
        Map<String, List<String>> expectedByFieldAndOrder = Map.of(
                "name:asc", List.of("m-folder", "n-folder", "a-file", "z-file"),
                "name:desc", List.of("n-folder", "m-folder", "z-file", "a-file"),
                "size:asc", List.of("m-folder", "n-folder", "a-file", "z-file"),
                "size:desc", List.of("n-folder", "m-folder", "z-file", "a-file"),
                "createTime:asc", List.of("m-folder", "n-folder", "a-file", "z-file"),
                "createTime:desc", List.of("n-folder", "m-folder", "z-file", "a-file"));

        for (Map.Entry<String, List<String>> entry : expectedByFieldAndOrder.entrySet()) {
            String[] keys = entry.getKey().split(":");
            List<String> actual = searchResultNames(keys[0], keys[1]);
            assertEquals(entry.getValue(), actual, "排序组合 name/size/createTime × asc/desc：" + entry.getKey());
        }
    }

    @Test
    void shouldOrderByTypeBeforeSortFieldInDirectoryQuery() {
        when(fileMapper.selectPage(any(), any())).thenReturn(new Page<>());

        FilePageQueryDto dto = new FilePageQueryDto();
        dto.setParentId("0");
        dto.setSortField("size");
        dto.setSortOrder("desc");
        fileQuerySupport.list(dto, "u1");

        String orderBy = capturedOrderBySql();
        assertTrue(orderBy.indexOf("type desc") >= 0, orderBy);
        assertTrue(orderBy.indexOf("type desc") < orderBy.indexOf("size desc"), orderBy);
    }

    @Test
    void shouldOrderByTypeFirstWhenSortFieldAbsent() {
        when(fileMapper.selectPage(any(), any())).thenReturn(new Page<>());

        FilePageQueryDto dto = new FilePageQueryDto();
        dto.setParentId("0");
        fileQuerySupport.list(dto, "u1");

        String orderBy = capturedOrderBySql();
        assertTrue(orderBy.indexOf("type desc") >= 0, orderBy);
        assertTrue(orderBy.indexOf("type desc") < orderBy.indexOf("create_time desc"), orderBy);
    }

    @SuppressWarnings("unchecked")
    private String capturedOrderBySql() {
        ArgumentCaptor<LambdaQueryWrapper<FileNode>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fileMapper).selectPage(any(), captor.capture());
        return captor.getValue().getExpression().getOrderBy().getSqlSegment().toLowerCase();
    }

    private List<String> searchResultNames(String sortField, String sortOrder) {
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        FileNode folderMiddle = node("f1", "u1", "m-folder", "folder", 100L, base.plusDays(1));
        FileNode folderLast = node("f2", "u1", "n-folder", "folder", 200L, base.plusDays(3));
        FileNode fileSmallest = node("f3", "u1", "a-file", "file", 10L, base);
        FileNode fileLargest = node("f4", "u1", "z-file", "file", 30L, base.plusDays(2));

        when(fileMapper.searchByName(eq("u1"), eq("report"), any()))
                .thenReturn(new ArrayList<>(List.of(fileSmallest, fileLargest, folderLast, folderMiddle)));
        when(fileConvert.poToVo(any(FileNode.class))).thenAnswer(invocation -> voOf(invocation.getArgument(0)));

        FilePageQueryDto dto = new FilePageQueryDto();
        dto.setName("report");
        dto.setSortField(sortField);
        dto.setSortOrder(sortOrder);
        dto.setPageNum(1L);
        dto.setPageSize(10L);

        return fileQuerySupport.list(dto, "u1").getRecords().stream()
                .map(FileNodeVo::getName)
                .toList();
    }

    private FileNode node(String id, String userId) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setUserId(userId);
        node.setName(id + ".txt");
        node.setType("file");
        return node;
    }

    private FileNode node(String id, String userId, String name, String type, Long size, LocalDateTime createTime) {
        FileNode node = node(id, userId);
        node.setName(name);
        node.setType(type);
        node.setSize(size);
        node.setCreateTime(createTime);
        return node;
    }

    private FileNodeVo vo(String id) {
        FileNodeVo vo = new FileNodeVo();
        vo.setId(id);
        vo.setName(id + ".txt");
        vo.setType("file");
        return vo;
    }

    private FileNodeVo voOf(FileNode node) {
        FileNodeVo vo = new FileNodeVo();
        vo.setId(node.getId());
        vo.setName(node.getName());
        vo.setType(node.getType());
        vo.setCreateTime(node.getCreateTime());
        return vo;
    }
}
