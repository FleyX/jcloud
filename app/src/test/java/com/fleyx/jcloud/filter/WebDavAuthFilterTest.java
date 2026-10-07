package com.fleyx.jcloud.filter;

import cn.hutool.crypto.digest.BCrypt;
import com.fleyx.jcloud.common.enums.UserStatus;
import com.fleyx.jcloud.config.AuthProperties;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.UserService;
import com.fleyx.jcloud.service.support.AuthRateLimitSupport;
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
import java.util.UUID;

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

    @Autowired
    private AuthRateLimitSupport authRateLimitSupport;

    @Autowired
    private AuthProperties authProperties;

    @TempDir
    Path tempDir;

    /**
     * 用例级随机客户端 IP（X-Forwarded-For），避免 Redis IP 窗口计数跨用例串扰。
     */
    private final String testIp = uniqueIp();

    private static String uniqueIp() {
        int a = Integer.parseInt(UUID.randomUUID().toString().replace("-", "").substring(0, 2), 16);
        int b = Integer.parseInt(UUID.randomUUID().toString().replace("-", "").substring(0, 2), 16);
        return "10.99." + a + "." + b;
    }

    private MockHttpServletRequest davRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("X-Forwarded-For", testIp);
        return request;
    }

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
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/admin/");
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
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldAllowEnabledWebDavUser() throws Exception {
        UserVo user = createUser("webdavEnabledUser", true);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
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
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/otheruser/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        UserVo user = createUser("webdavWrongPasswordUser", true);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "wrong-password"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").contains("Basic"));
    }

    @Test
    void shouldRejectDisabledStatusUser() throws Exception {
        UserVo user = createUser("webdavDisabledStatusUser", true);
        User update = new User();
        update.setId(user.getId());
        update.setStatus(UserStatus.DISABLED.getCode());
        userMapper.updateById(update);

        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldRejectNonDavUri() throws Exception {
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("GET", "/other/path");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chained = {false};
        FilterChain chain = (req, res) -> chained[0] = true;
        filter.doFilterInternal(request, response, chain);
        assertEquals(401, response.getStatus());
        assertTrue(!chained[0]);
    }

    @Test
    void shouldRejectBasicHeaderWithoutColon() throws Exception {
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/admin/");
        String encoded = Base64.getEncoder().encodeToString("usernamepassword".getBytes());
        request.addHeader("Authorization", "Basic " + encoded);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldRejectMalformedBase64BasicHeader() throws Exception {
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/admin/");
        request.addHeader("Authorization", "Basic !!!not-base64!!!");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldRejectUnknownUser() throws Exception {
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/webdavGhostUser/");
        request.addHeader("Authorization", basicAuth("webdavGhostUser", "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) -> {});
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldReturn429AfterConsecutiveFailures() throws Exception {
        // 用户名按运行随机化：Redis 锁定键有 TTL，固定名跨运行串扰
        UserVo user = createUser("webdavRl" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), true);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        int maxFailures = authProperties.getRateLimit().getMaxFailures();

        for (int i = 0; i < maxFailures; i++) {
            MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
            request.addHeader("Authorization", basicAuth(user.getUsername(), "wrong-password"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, (req, res) -> {});
            assertEquals(401, response.getStatus());
        }

        // 第 maxFailures+1 次即使凭据正确也拒绝：429 且不携带 WWW-Authenticate（避免客户端无限重试）
        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chained = {false};
        filter.doFilterInternal(request, response, (req, res) -> chained[0] = true);
        assertEquals(429, response.getStatus());
        assertEquals("尝试次数过多，请稍后再试", response.getErrorMessage());
        assertTrue(response.getHeader("WWW-Authenticate") == null);
        assertTrue(!chained[0]);
    }

    @Test
    void shouldNotCountRequestsWithoutCredentials() throws Exception {
        UserVo user = createUser("webdavNc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), true);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);
        int maxFailures = authProperties.getRateLimit().getMaxFailures();

        // 无凭据的首次挑战请求不计入失败：即使超过阈值次数，正确凭据仍应放行
        for (int i = 0; i < maxFailures + 1; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(davRequest("PROPFIND", "/dav/" + user.getUsername() + "/"),
                    response, (req, res) -> {});
            assertEquals(401, response.getStatus());
        }

        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chained = {false};
        FilterChain chain = (req, res) -> chained[0] = true;
        filter.doFilterInternal(request, response, chain);
        assertEquals(200, response.getStatus());
        assertTrue(chained[0]);
    }

    @Test
    void successShouldClearFailureCount() throws Exception {
        UserVo user = createUser("webdavCc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), true);
        WebDavAuthFilter filter = new WebDavAuthFilter(userMapper, authRateLimitSupport);

        // 先失败 2 次
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
            request.addHeader("Authorization", basicAuth(user.getUsername(), "wrong-password"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, (req, res) -> {});
            assertEquals(401, response.getStatus());
        }

        // 成功后计数清零
        MockHttpServletRequest ok = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        ok.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        filter.doFilterInternal(ok, new MockHttpServletResponse(), (req, res) -> {});

        // 再失败 1 次未达阈值，正确凭据仍放行
        MockHttpServletRequest fail = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        fail.addHeader("Authorization", basicAuth(user.getUsername(), "wrong-password"));
        filter.doFilterInternal(fail, new MockHttpServletResponse(), (req, res) -> {});

        MockHttpServletRequest request = davRequest("PROPFIND", "/dav/" + user.getUsername() + "/");
        request.addHeader("Authorization", basicAuth(user.getUsername(), "123456"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chained = {false};
        FilterChain chain = (req, res) -> chained[0] = true;
        filter.doFilterInternal(request, response, chain);
        assertEquals(200, response.getStatus());
        assertTrue(chained[0]);
    }

    private String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
    }
}
