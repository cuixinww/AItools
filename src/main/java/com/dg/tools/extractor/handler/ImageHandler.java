package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import com.dg.tools.extractor.model.UnpackResult;

import java.io.InputStream;
import java.util.Set;

/**
 * 图片处理器（ImageHandler）。
 *
 * 作为解析树的「叶子节点」：图片文件本身没有可进一步解析的内部文本结构，
 * 因此 unpack 与 extract 都只是把图片原始字节原样保留，
 * 供上层在 media/ 目录中保存，并交由阶段 1.5 的视觉模型生成描述。
 *
 * 支持的图片扩展名：png / jpg / jpeg / gif / bmp / tiff / tif / webp。
 */
public class ImageHandler implements DocumentHandler {

    /** 支持的图片扩展名集合。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tiff", "tif", "webp"
    );

    /** 单张图片大小上限（100 MB）。 */
    private static final int MAX_IMAGE_BYTES = 100 * 1024 * 1024;

    /**
     * 是否支持该文件：按扩展名判断是否为受支持的图片类型。
     */
    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 阶段 1 拆包：把整张图片作为 image 元素原样保留。
     */
    @Override
    public UnpackResult unpack(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        UnpackResult unpacked = new UnpackResult("image", fileName);
        try {
            byte[] data = is.readAllBytes();
            unpacked.addImage(new ImageFile(fileName, 0, data, normalizeFormat(fileName)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to unpack image: " + fileName, e);
        }
        return unpacked;
    }

    /**
     * 阶段 2 解析：同样原样保留图片字节（含大小上限校验）。
     */
    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("image", fileName);

        try {
            byte[] data = is.readAllBytes();
            if (data.length > MAX_IMAGE_BYTES) {
                throw new RuntimeException("Image too large: " + fileName
                        + " (" + (data.length / (1024 * 1024)) + " MB)");
            }
            result.addImage(new ImageFile(fileName, 0, data, normalizeFormat(fileName)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read image: " + fileName, e);
        }

        return result;
    }

    /** 把扩展名归一化（jpeg→jpg，tif→tiff），未知则保持原样。 */
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
