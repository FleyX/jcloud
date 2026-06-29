package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FilePathUtil 单元测试。
 */
class FilePathUtilTest {

    @Test
    void shouldBuildRootLevelPathName() {
        assertEquals("/a.txt", FilePathUtil.buildPathName("/", "a.txt"));
        assertEquals("/docs", FilePathUtil.buildPathName("/", "docs"));
    }

    @Test
    void shouldBuildNestedPathName() {
        assertEquals("/docs/report.pdf", FilePathUtil.buildPathName("/docs", "report.pdf"));
        assertEquals("/a/b/c.txt", FilePathUtil.buildPathName("/a/b", "c.txt"));
    }

    @Test
    void shouldGetParentPath() {
        assertEquals("/", FilePathUtil.parentOf("/a.txt"));
        assertEquals("/", FilePathUtil.parentOf("/docs"));
        assertEquals("/docs", FilePathUtil.parentOf("/docs/report.pdf"));
        assertEquals("/a/b", FilePathUtil.parentOf("/a/b/c.txt"));
        assertEquals("/", FilePathUtil.parentOf("/"));
    }

    @Test
    void shouldGetNodeName() {
        assertEquals("a.txt", FilePathUtil.nameOf("/a.txt"));
        assertEquals("docs", FilePathUtil.nameOf("/docs"));
        assertEquals("report.pdf", FilePathUtil.nameOf("/docs/report.pdf"));
    }

    @Test
    void shouldStripLeadingSlash() {
        assertEquals("a.txt", FilePathUtil.stripLeadingSlash("/a.txt"));
        assertEquals("docs/report.pdf", FilePathUtil.stripLeadingSlash("/docs/report.pdf"));
        assertEquals("", FilePathUtil.stripLeadingSlash("/"));
        assertEquals("", FilePathUtil.stripLeadingSlash(null));
    }

    @Test
    void shouldResolvePhysicalPathForFile() {
        FileNode node = new FileNode();
        node.setUserId("1");
        node.setName("report.pdf");
        node.setPath(FileNodeConstants.ROOT_ID + ".docs");

        StorageSpace space = new StorageSpace();
        space.setPath("/storage");

        Map<String, String> cache = new HashMap<>();
        cache.put("docs", "docs");
        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(space, "alice", cache);
        Path path = FilePathUtil.resolvePhysicalPath(node, ctx);
        assertEquals(Path.of("/storage/files/alice/docs/report.pdf"), path);
    }

    @Test
    void shouldResolvePhysicalPathForFolder() {
        FileNode node = new FileNode();
        node.setUserId("1");
        node.setName("docs");
        node.setPath(FileNodeConstants.ROOT_ID);

        StorageSpace space = new StorageSpace();
        space.setPath("/storage");

        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(space, "alice");
        Path path = FilePathUtil.resolvePhysicalPath(node, ctx);
        assertEquals(Path.of("/storage/files/alice/docs"), path);
    }

    @Test
    void shouldResolvePhysicalPathFromParentAndName() {
        StorageSpace space = new StorageSpace();
        space.setPath("/storage");

        Path path = FilePathUtil.resolvePhysicalPath(space, "bob", "/docs", "report.pdf");
        assertEquals(Path.of("/storage/files/bob/docs/report.pdf"), path);
    }
}
