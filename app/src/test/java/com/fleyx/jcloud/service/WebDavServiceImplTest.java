package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebDAV 服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebDavServiceImplTest {

    @Autowired
    private WebDavService webDavService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @TempDir
    Path tempDir;

    private UserVo createUser(String username) throws Exception {
        Path spacePath = Files.createTempDirectory(tempDir, "space-" + username);
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto dto = new UserSaveDto();
        dto.setUsername(username);
        dto.setPassword("123456");
        dto.setStorageSpaceId(space.getId());
        dto.setQuota(10L);
        dto.setQuotaUnit("GB");
        return userService.saveUser(dto);
    }

    @Test
    void shouldReturnDavCapabilitiesOnOptions() throws Exception {
        UserVo user = createUser("webdavOptionsUser");
        UserContext.set(new com.fleyx.jcloud.common.context.CurrentUser(user.getId(), user.getUsername()));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/dav/" + user.getUsername());
            MockHttpServletResponse response = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), request, response);
            assertEquals(200, response.getStatus());
            assertTrue(response.getHeader("DAV").contains("1"));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldPropFindRootFolder() throws Exception {
        UserVo user = createUser("webdavPropUser");
        UserContext.set(new com.fleyx.jcloud.common.context.CurrentUser(user.getId(), user.getUsername()));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
            MockHttpServletResponse response = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), request, response);
            assertEquals(207, response.getStatus());
            String body = response.getContentAsString();
            assertTrue(body.contains("<D:multistatus"));
            assertTrue(body.contains("<D:collection/>"));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldCreateFolderAndPutFile() throws Exception {
        UserVo user = createUser("webdavWriteUser");
        UserContext.set(new com.fleyx.jcloud.common.context.CurrentUser(user.getId(), user.getUsername()));
        try {
            MockHttpServletRequest mkcol = new MockHttpServletRequest("MKCOL", "/dav/" + user.getUsername() + "/docs");
            MockHttpServletResponse mkcolResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), mkcol, mkcolResp);
            assertEquals(201, mkcolResp.getStatus());

            MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/dav/" + user.getUsername() + "/docs/readme.txt");
            put.setContent("hello".getBytes());
            MockHttpServletResponse putResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), put, putResp);
            assertEquals(201, putResp.getStatus());

            MockHttpServletRequest get = new MockHttpServletRequest("GET", "/dav/" + user.getUsername() + "/docs/readme.txt");
            MockHttpServletResponse getResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), get, getResp);
            assertEquals(200, getResp.getStatus());
            assertEquals("hello", getResp.getContentAsString());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldCopyAndMoveFile() throws Exception {
        UserVo user = createUser("webdavMoveUser");
        UserContext.set(new com.fleyx.jcloud.common.context.CurrentUser(user.getId(), user.getUsername()));
        try {
            MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/dav/" + user.getUsername() + "/a.txt");
            put.setContent("data".getBytes());
            webDavService.handle(user.getUsername(), put, new MockHttpServletResponse());

            MockHttpServletRequest copy = new MockHttpServletRequest("COPY", "/dav/" + user.getUsername() + "/a.txt");
            copy.addHeader("Destination", "http://localhost:8080/dav/" + user.getUsername() + "/b.txt");
            MockHttpServletResponse copyResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), copy, copyResp);
            assertEquals(201, copyResp.getStatus());

            MockHttpServletRequest move = new MockHttpServletRequest("MOVE", "/dav/" + user.getUsername() + "/a.txt");
            move.addHeader("Destination", "http://localhost:8080/dav/" + user.getUsername() + "/c.txt");
            MockHttpServletResponse moveResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), move, moveResp);
            assertEquals(204, moveResp.getStatus());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldDeleteFile() throws Exception {
        UserVo user = createUser("webdavDeleteUser");
        UserContext.set(new com.fleyx.jcloud.common.context.CurrentUser(user.getId(), user.getUsername()));
        try {
            MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/dav/" + user.getUsername() + "/del.txt");
            put.setContent("x".getBytes());
            webDavService.handle(user.getUsername(), put, new MockHttpServletResponse());

            MockHttpServletRequest del = new MockHttpServletRequest("DELETE", "/dav/" + user.getUsername() + "/del.txt");
            MockHttpServletResponse delResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), del, delResp);
            assertEquals(204, delResp.getStatus());

            MockHttpServletRequest get = new MockHttpServletRequest("GET", "/dav/" + user.getUsername() + "/del.txt");
            MockHttpServletResponse getResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), get, getResp);
            assertEquals(404, getResp.getStatus());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void shouldReturn423OnRelockOfLockedResource() throws Exception {
        UserVo user = createUser("webdavRelockUser");
        UserContext.set(new com.fleyx.jcloud.common.context.CurrentUser(user.getId(), user.getUsername()));
        try {
            MockHttpServletRequest first = new MockHttpServletRequest("LOCK", "/dav/" + user.getUsername() + "/locked.txt");
            MockHttpServletResponse firstResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), first, firstResp);
            assertEquals(200, firstResp.getStatus());
            assertNotNull(firstResp.getHeader("Lock-Token"));

            // 资源已被锁定：重复 LOCK 按 RFC 4918 返回 423 Locked
            MockHttpServletRequest second = new MockHttpServletRequest("LOCK", "/dav/" + user.getUsername() + "/locked.txt");
            MockHttpServletResponse secondResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), second, secondResp);
            assertEquals(423, secondResp.getStatus());

            // 清理：用旧 token 解锁，避免污染共享 Redis（test 配置 database 1）
            MockHttpServletRequest unlock = new MockHttpServletRequest("UNLOCK", "/dav/" + user.getUsername() + "/locked.txt");
            unlock.addHeader("Lock-Token", firstResp.getHeader("Lock-Token"));
            MockHttpServletResponse unlockResp = new MockHttpServletResponse();
            webDavService.handle(user.getUsername(), unlock, unlockResp);
            assertEquals(204, unlockResp.getStatus());
        } finally {
            UserContext.clear();
        }
    }
}
