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
import org.junit.jupiter.api.Assumptions;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void shouldStreamPdfDirectlyWithoutCache() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        byte[] pdfBytes = minimalPdfBytes();
        MultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", pdfBytes);

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);
        PreviewResult result = filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.OFFICE);

        assertEquals("application/pdf", result.getContentType());
        assertArrayEquals(pdfBytes, result.getInputStream().readAllBytes());
        // PDF 原文件直接返回，不生成预览缓存记录
        long cacheCount = previewFileMapper.selectCount(
                new LambdaQueryWrapper<PreviewFile>()
                        .eq(PreviewFile::getFileNodeId, uploaded.getId()));
        assertEquals(0, cacheCount);
    }

    @Test
    void shouldRejectUnsupportedOfficeFormat() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        MultipartFile file = buildFile("archive.zip", "not an office file");

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);

        assertThrows(com.fleyx.jcloud.common.exception.BusinessException.class,
                () -> filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.OFFICE));
    }

    @Test
    void shouldRejectOversizedOfficeFile() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        // 测试环境转换上限为 2048 字节
        byte[] bigContent = new byte[4096];
        MultipartFile file = new MockMultipartFile("file", "big.docx", "application/octet-stream", bigContent);

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);

        com.fleyx.jcloud.common.exception.BusinessException ex =
                assertThrows(com.fleyx.jcloud.common.exception.BusinessException.class,
                        () -> filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.OFFICE));
        assertTrue(ex.getMessage().contains("文件过大"));
    }

    @Test
    void shouldConvertDocToPdf() throws Exception {
        Assumptions.assumeTrue(isSofficeAvailable(), "soffice 不可用，跳过真实转换测试");
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        MultipartFile file = new MockMultipartFile("file", "note.doc", "application/msword",
                "hello jcloud office preview".getBytes());

        FileNodeVo uploaded = fileService.upload(file, user.getId(), FileNodeConstants.ROOT_ID, null);
        PreviewResult result = filePreviewService.preview(uploaded.getId(), user.getId(), PreviewType.OFFICE);

        byte[] bytes = result.getInputStream().readAllBytes();
        assertTrue(bytes.length > 0);
        byte[] head = new byte[4];
        System.arraycopy(bytes, 0, head, 0, 4);
        assertArrayEquals("%PDF".getBytes(), head);
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

    private byte[] minimalPdfBytes() {
        return ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj\n"
                + "trailer<</Root 1 0 R>>\n%%EOF").getBytes(StandardCharsets.UTF_8);
    }

    private boolean isSofficeAvailable() {
        try {
            Process process = new ProcessBuilder("soffice", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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
