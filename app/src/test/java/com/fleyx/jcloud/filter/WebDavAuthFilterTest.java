package com.fleyx.jcloud.filter;

import cn.hutool.crypto.digest.BCrypt;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebDAV 认证过滤器测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebDavAuthFilterTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @TempDir
    Path tempDir;

    private UserVo createUser(String username, boolean webdavEnabled) throws Exception {
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
        UserVo saved = userService.saveUser(dto);

        User update = new User();
        update.setId(saved.getId());
        update.setWebdavEnabled(webdavEnabled);
        userMapper.updateById(update);
        return saved;
    }

    @Test
    void shouldRejectRequestWithoutAuth() throws Exception {
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/dav/admin/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chained = {false};
        FilterChain chain = (req, res) -> chained[0] = true;
        filter.doFilterInternal(request, response, chain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").contains("Basic"));
    }

    @Test
    void shouldRejectDisabledWebDavUser() throws Exception {
        UserVo user = createUser("webdavDisabledUser", false);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldAllowEnabledWebDavUser() throws Exception {
        UserVo user = createUser("webdavEnabledUser", true);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chained = {false};
        FilterChain chain = (req, res) -> chained[0] = true;
        filter.doFilterInternal(request, response, chain);
        assertEquals(200, response.getStatus());
        assertTrue(chained[0]);
    }

    @Test
    void shouldRejectMismatchedUrlUser() throws Exception {
        UserVo user = createUser("webdavMismatchUser", true);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/dav/otheruser/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    private String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
    }
}
