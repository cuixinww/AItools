package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Set;

/**
 * 图片处理器（ImageHandler）。
 *
 * 作为解析树的「叶子节点」：图片文件本身没有可进一步解析的内部文本结构，
 * 因此 unpack 与 extract 都只是把图片原始字节原样保留，
 * 供上层在 media/ 目录中保存，并交由阶段 1.5 的视觉模型生成描述。
 *
 * 对应 SPEC §6 IMAGE + §9 路由表：图片 → ImageHandler。
 */
@Component
@Slf4j
public class ImageHandler extends AbstractHandler {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tiff", "tif", "webp"
    );

    private final int maxImageBytes;

    public ImageHandler() {
        this.maxImageBytes = 100 * 1024 * 1024;
    }

    @Autowired
    public ImageHandler(@Value("${extractor.image.max-bytes:104857600}") int maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("image", fileName);
        try {
            byte[] data = is.readAllBytes();
            if (data.length > maxImageBytes) {
                log.warn("Image too large: {} ({} MB)", fileName, data.length / (1024 * 1024));
                unpacked.addError("image too large: " + fileName);
                return unpacked;
            }
            unpacked.addImage(new ImageFile(fileName, 0, data, normalizeFormat(fileName)));
        } catch (Exception e) {
            log.error("Failed to unpack image: {}", fileName, e);
            unpacked.addError("unpack error: " + e.getMessage());
        }
        return unpacked;
    }

    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("image", fileName);

        try {
            byte[] data = is.readAllBytes();
            if (data.length > maxImageBytes) {
                log.warn("Image too large: {} ({} MB)", fileName, data.length / (1024 * 1024));
                result.addError("image too large: " + fileName);
                return result;
            }
            result.addImage(new ImageFile(fileName, 0, data, normalizeFormat(fileName)));
        } catch (Exception e) {
            log.error("Failed to read image: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    private static String normalizeFormat(String fileName) {
        if (fileName == null) return "png";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "png";
        String ext = fileName.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "jpeg" -> "jpg";
            case "tif" -> "tiff";
            default -> ext;
        };
    }
}
