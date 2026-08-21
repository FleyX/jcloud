package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.FileOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单 04 NFO 合并写回：写回层验证「目录已有含 actor 的 NFO → 写回复用后 actor 仍在、管理字段已更新」，
 * 以及不存在 NFO 时全新生成。类级事务回滚。
 */
@SpringBootTest
@Transactional
class MediaNfoMergeWriteBackTest extends IntegrationTestBase {

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private MediaArtworkPersistSupport mediaArtworkPersistSupport;

    @Autowired
    private FileMapper fileMapper;

    @Test
    void shouldPreserveUnknownFieldsWhenWritingBackOverExistingNfo() {
        UserWithSpace uw = prepareUserWithStorageSpace();
        String userId = uw.user().getId();
        FileNode folder = fileMapper.selectById(
                createFolder(userId, "movie", FileNodeConstants.ROOT_ID).getId());
        assertNotNull(folder);

        String original = """
                <movie>
                  <title>旧标题</title>
                  <rating>1.0</rating>
                  <genre>科幻</genre>
                  <actor><name>某演员</name><role>主角</role></actor>
                  <uniqueid type="imdb">tt1375666</uniqueid>
                </movie>
                """;
        mediaArtworkPersistSupport.writeNfoXml(folder, "movie.nfo", original);

        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTmdbId(27205L);
        metadata.setTitle("新标题");
        metadata.setReleaseDate("2010-07-16");
        metadata.setVoteAverage(7.5);
        metadata.setGenres("战争");
        mediaArtworkPersistSupport.writeNfoXml(folder, "movie.nfo", metadata, null, null);

        FileNode nfo = mediaArtworkPersistSupport.findChildFile(userId, folder.getId(), "movie.nfo");
        assertNotNull(nfo);
        String written = new String(mediaArtworkPersistSupport.readFileBytes(nfo), StandardCharsets.UTF_8);

        // 未知元素保留
        assertTrue(written.contains("某演员"));
        assertTrue(written.contains("tt1375666"));
        // 管理字段已更新；genre 整体替换为战争、旧值移除
        assertTrue(written.contains("新标题"));
        assertTrue(written.contains("27205"));
        assertTrue(written.contains("战争"));
        assertTrue(!written.contains("旧标题"));
        assertTrue(!written.contains("科幻"));
        assertTrue(!written.contains("<rating>1.0</rating>"));
    }

    @Test
    void shouldGenerateFreshNfoWhenNoneExists() {
        UserWithSpace uw = prepareUserWithStorageSpace();
        String userId = uw.user().getId();
        FileNode folder = fileMapper.selectById(
                createFolder(userId, "movie2", FileNodeConstants.ROOT_ID).getId());
        assertNotNull(folder);

        MediaMetadata metadata = new MediaMetadata();
        metadata.setOwnerType(MediaMetadataOwnerType.MOVIE.getCode());
        metadata.setTitle("全新片");
        metadata.setGenres("冒险");
        mediaArtworkPersistSupport.writeNfoXml(folder, "movie.nfo", metadata, null, null);

        FileNode nfo = mediaArtworkPersistSupport.findChildFile(userId, folder.getId(), "movie.nfo");
        assertNotNull(nfo);
        String written = new String(mediaArtworkPersistSupport.readFileBytes(nfo), StandardCharsets.UTF_8);
        assertTrue(written.contains("全新片"));
        assertTrue(written.contains("冒险"));
    }

    private FileNodeVo createFolder(String userId, String name, String parentId) {
        FileCreateFolderDto dto = new FileCreateFolderDto();
        dto.setParentId(parentId);
        dto.setName(name);
        return fileOperationService.createFolder(dto, userId);
    }
}
