package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.service.FilePreviewGenerator;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * 图片缩略图生成器。
 */
@Component
public class ImageThumbnailGenerator implements FilePreviewGenerator {

    private static final int MAX_SIZE = 512;

    @Override
    public PreviewType supportedType() {
        return PreviewType.THUMBNAIL;
    }

    @Override
    public void generate(Path sourcePath, Path targetPath) throws Exception {
        BufferedImage original = ImageIO.read(sourcePath.toFile());
        if (original == null) {
            throw new IllegalArgumentException("无法读取图片文件");
        }

        int width = original.getWidth();
        int height = original.getHeight();
        if (width > MAX_SIZE || height > MAX_SIZE) {
            double ratio = Math.min((double) MAX_SIZE / width, (double) MAX_SIZE / height);
            width = (int) (width * ratio);
            height = (int) (height * ratio);
        }

        Image scaled = original.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumbnail.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();

        java.nio.file.Files.createDirectories(targetPath.getParent());
        ImageIO.write(thumbnail, "jpg", targetPath.toFile());
    }
}
