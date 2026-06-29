package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.mapper.PreviewFileMapper;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.po.PreviewFile;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件预览服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FilePreviewServiceTest {

    @Autowired
    private FilePreviewService filePreviewService;

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private PreviewFileMapper previewFileMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @TempDir
    static Path tempDir;

    @Test
    void shouldGenerateImageThumbnail() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        MultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", createImageBytes());

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);
        PreviewResult result = filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.THUMBNAIL);

        assertEquals("image/jpeg", result.getContentType());
        assertTrue(result.getSize() > 0);
        BufferedImage thumbnail = ImageIO.read(result.getInputStream());
        assertNotNull(thumbnail);
        assertTrue(thumbnail.getWidth() <= 512);
        assertTrue(thumbnail.getHeight() <= 512);
    }

    @Test
    void shouldGenerateTextPreview() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        String content = "Hello, jcloud preview!";
        MultipartFile file = buildFile("note.txt", content);

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);
        PreviewResult result = filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.TEXT);

        assertEquals("text/plain", result.getContentType());
        String previewContent = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(content, previewContent);
    }

    @Test
    void shouldReuseCachedPreview() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        MultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", createImageBytes());
        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);

        filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.THUMBNAIL);
        PreviewFile recordBefore = previewFileMapper.selectList(
                new LambdaQueryWrapper<PreviewFile>()
                        .eq(PreviewFile::getFileNodeId, uploaded.getId())
                        .eq(PreviewFile::getType, PreviewType.THUMBNAIL.getCode())
        ).get(0);

        PreviewResult result = filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.THUMBNAIL);
        PreviewFile recordAfter = previewFileMapper.selectById(recordBefore.getId());

        assertEquals(recordBefore.getSize(), recordAfter.getSize());
        assertEquals("image/jpeg", result.getContentType());
    }

    @Test
    void shouldStorePreviewInSystemSpace() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        MultipartFile file = buildFile("note.txt", "system space check");

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);
        filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.TEXT);

        PreviewFile record = previewFileMapper.selectList(
                new LambdaQueryWrapper<PreviewFile>()
                        .eq(PreviewFile::getFileNodeId, uploaded.getId())
        ).get(0);

        Path systemPath = userWithSpace.spacePath().resolve("system");
        Path previewPath = systemPath.resolve(record.getRelativePath());
        assertTrue(Files.exists(previewPath));
        assertTrue(previewPath.startsWith(systemPath));
    }

    @Test
    void shouldGenerateVideoPoster() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        Path videoPath = createTestVideo();
        MultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4", Files.readAllBytes(videoPath));

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);
        PreviewResult result = filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.POSTER);

        assertEquals("image/jpeg", result.getContentType());
        assertTrue(result.getSize() > 0);
        BufferedImage poster = ImageIO.read(result.getInputStream());
        assertNotNull(poster);
    }

    @Test
    void shouldRejectPreviewFromOtherUser() throws Exception {
        UserWithSpace userAWithSpace = prepareUserWithStorageSpace();
        UserWithSpace userBWithSpace = prepareUserWithStorageSpace();
        UserVo userA = userAWithSpace.user();
        UserVo userB = userBWithSpace.user();
        MultipartFile file = buildFile("private.txt", "private content");

        FileNodeVo uploaded = fileService.upload(file, userA.getId(), FileNodeConstants.ROOT_ID, null);

        assertThrows(com.fleyx.jcloud.common.exception.BusinessException.class,
                () -> filePreviewService.preview(uploaded.getId(), userB.getId(), PreviewType.TEXT));
    }

    private Path createTestVideo() throws Exception {
        Path videoPath = tempDir.resolve("test-video-" + System.nanoTime() + ".mp4");
        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-f", "lavfi",
                "-i", "testsrc=duration=2:size=320x240:rate=1",
                "-pix_fmt", "yuv420p",
                videoPath.toAbsolutePath().toString()
        );
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.waitFor();
        if (process.exitValue() != 0 || !Files.exists(videoPath)) {
            throw new IllegalStateException("测试视频生成失败");
        }
        return videoPath;
    }

    private byte[] createImageBytes() throws Exception {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    private UserWithSpace prepareUserWithStorageSpace() {
        return prepareUserWithStorageSpace(10737418240L);
    }

    private UserWithSpace prepareUserWithStorageSpace(long quota) {
        Path spacePath = tempDir.resolve("space-" + System.nanoTime());
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        spaceDto.setType("USER");
        StorageSpaceVo space = storageSpaceService.save(spaceDto);
        systemConfigService.setValue("system.storage.space.id", String.valueOf(space.getId()));

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(toQuotaValue(quota));
        userDto.setQuotaUnit(toQuotaUnit(quota));
        UserVo user = userService.saveUser(userDto);
        UserContext.set(new CurrentUser(user.getId(), user.getUsername()));

        return new UserWithSpace(user, spacePath);
    }

    private static long toQuotaValue(long quotaBytes) {
        return quotaBytes == 10737418240L ? 10L : quotaBytes;
    }

    private static String toQuotaUnit(long quotaBytes) {
        return quotaBytes == 10737418240L ? "GB" : "B";
    }

    private record UserWithSpace(UserVo user, Path spacePath) {
    }
}
