package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Set;

/**
 * 图片处理器（叶子节点）。
 *
 * 作为解析树的叶子节点：图片文件本身没有可进一步解析的内部文本结构，
 * 因此 unpack 与 extract 都只是把图片原始字节原样保留。
 * Phase 1.5 IMAGE 阶段的视觉模型会接收这些字节并生成描述 JSON。
 *
 * <p>大小防护：通过 {@link #readWithSizeLimit} 流式读取，
 * 每 8KB 检查一次累计大小，超过阈值则丢弃并记录错误。</p>
 *
 * @see ImageDescriber
 */
@Component
@Slf4j
public class ImageHandler extends AbstractHandler {

    /** 支持的图片格式后缀集合。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tiff", "tif", "webp"
    );

    /** 图片最大字节数。默认 100 MB。超过此值的图片会被拒绝。 */
    private final int maxImageBytes;

    /** 无参构造函数：使用默认阈值 100 MB。被 Spring 忽略，仅作安全回退。 */
    public ImageHandler() {
        this.maxImageBytes = 100 * 1024 * 1024;
    }

    /**
     * 通过 Spring 配置注入最大图片大小。
     * @param maxImageBytes 最大字节数
     */
    @Autowired
    public ImageHandler(@Value("${extractor.image.max-bytes:104857600}") int maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    /** 判断当前文件名是否为支持的图片扩展名（png/jpg/jpeg/gif/bmp/tiff/tif/webp）。 */
    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /** Phase 1 拆包：流式读取图片字节，超出限制时拒绝。 */
    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        return readImage(is, fileName, "unpack");
    }

    /** Phase 2 解析：同 unpack（图片为叶子节点，无更多内容可提取）。 */
    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        return readImage(is, fileName, "parse");
    }

    /**
     * 流式读取图片字节，进行大小校验后封装为 ImageFile。
     * 避免超大图片先全部读入堆再被拒绝的 OOM 风险。
     */
    private ExtractionResult readImage(InputStream is, String fileName, String phase) {
        ExtractionResult result = ExtractionResult.of("image", fileName);
        try {
            byte[] data = readWithSizeLimit(is, fileName);
            // readWithSizeLimit 在超限时返回 null
            if (data == null) {
                result.addError("image too large: " + fileName);
                return result;
            }
            // 二次安全检查（理论上已被 readWithSizeLimit 拦截）
            if (data.length > maxImageBytes) {
                log.warn("Image too large: {} ({} MB)", fileName, data.length / (1024 * 1024));
                result.addError("image too large: " + fileName);
                return result;
            }
            result.addImage(new ImageFile(fileName, 0, data, normalizeFormat(fileName)));
        } catch (Exception e) {
            log.error("Failed to {} image: {}", phase, fileName, e);
            result.addError(phase + " error: " + e.getMessage());
        }
        return result;
    }

    /**
     * 流式读取输入流，每 8KB 检查一次累计大小。
     * 超过 {@link #maxImageBytes} 上限时返回 null 并记录警告。
     *
     * @return 完整的字节数组；超限则返回 null
     */
    private byte[] readWithSizeLimit(InputStream is, String fileName) throws Exception {
        byte[] buf = new byte[8192];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int total = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > maxImageBytes) {
                log.warn("Image size limit exceeded during streaming read: {} (>{}) MB), discarding",
                        fileName, maxImageBytes / (1024 * 1024));
                return null;
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * 从文件名中提取规范化格式标识。
     * "jpeg" → "jpg", "tif" → "tiff", 其他直接返回。
     */
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
