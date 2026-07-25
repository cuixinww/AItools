package com.dg.tools.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;

import java.util.Map;

import static java.util.Map.entry;

/**
 * 文件类型探测器（TypeDetector）。
 *
 * 职责：根据「文件字节内容」或「文件名提示」判断文档的真实类型，
 * 并映射为统一的扩展名（小写、不含点），供后续处理器路由使用。
 *
 * 底层依赖 Apache Tika 做 MIME 类型探测，再结合一张「已知 MIME → 扩展名」映射表。
 * 对于探测不到或探测为通用类型（octet-stream / text/plain / zip）的情况，
 * 优先信任文件名后缀，以避免把 docx / xlsx 这类「本质也是 zip」的容器误判成 zip。
 */
@Slf4j
public class TypeDetector {

    /**
     * 已知 MIME 类型到扩展名的映射表。
     * 注意：application/zip 故意不在此表中映射 —— 因为 docx / xlsx 底层本就是 zip 容器，
     * 若映射了会把 "report.docx" 误识别为 zip；此处让它落到「按文件名回退」分支。
     */
    private static final Map<String, String> MIME_TO_EXTENSION = Map.ofEntries(
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/pdf", "pdf"),
            // application/zip 故意不在此映射 —— docx/xlsx 也是 zip 容器，
            // 让其回退到文件名探测，避免 "report.docx" 被误判为 zip
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("image/tiff", "tiff"),
            Map.entry("image/webp", "webp")
    );

    /** Tika 内置的完整 MIME 类型表（用于最后兜底查扩展名）。 */
    private static final MimeTypes ALL_MIME_TYPES = MimeTypes.getDefaultMimeTypes();

    /** Tika 实例，用于基于字节内容探测 MIME 类型。 */
    private final Tika tika = new Tika();

    /**
     * 探测并返回文件扩展名（小写、不含点）。
     *
     * 判定优先级：
     *   1) 字节内容能高置信识别出已知类型 → 直接用映射表；
     *   2) 探测为通用类型（octet-stream / text/plain / zip）→ 信任文件名后缀（排除 "bin"）；
     *   3) 用 Tika 自带扩展名兜底；
     *   4) 仍失败则用文件名后缀兜底。
     *
     * @param data          文件字节内容（可为空）
     * @param hintFileName  原始文件名（用于后缀回退）
     * @return 扩展名字符串，如 "docx"；无法判断时回退为 "bin"
     */
    public String detectExtension(byte[] data, String hintFileName) {
        if (data == null || data.length == 0) {
            return fallbackFromName(hintFileName);
        }

        String mime = detectMimeType(data);

        // 若 Tika 自信地识别出一个已知类型，直接使用
        String ext = MIME_TO_EXTENSION.get(mime);
        if (ext != null) {
            return ext;
        }

        // 若 Tika 只给出通用类型，则优先信任文件扩展名
        if ("application/octet-stream".equals(mime) || "text/plain".equals(mime)
                || "application/zip".equals(mime)) {
            String fallback = fallbackFromName(hintFileName);
            if (fallback != null && !"bin".equals(fallback)) {
                return fallback;
            }
        }

        // 尝试使用 Tika 自身的扩展名映射（如 "image/x-citrix-png" → "png" 之类）
        try {
            MimeType mimeType = ALL_MIME_TYPES.forName(mime);
            if (mimeType != null) {
                String tikaExt = mimeType.getExtension();
                if (tikaExt != null && !tikaExt.isEmpty()) {
                    return tikaExt.replace(".", "");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get MIME extension for: {}", mime, e);
        }

        return fallbackFromName(hintFileName);
    }

    /**
     * 基于字节内容探测 MIME 类型。
     *
     * @param data 文件字节内容
     * @return MIME 类型字符串；内容为空或探测异常时返回 "application/octet-stream"
     */
    public String detectMimeType(byte[] data) {
        if (data == null || data.length == 0) {
            return "application/octet-stream";
        }

        try {
            return tika.detect(data);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    /**
     * 从文件名中提取扩展名作为兜底。
     * 仅当文件名包含合法的 "." 后缀时返回小写扩展名，否则返回 "bin"。
     */
    private String fallbackFromName(String hintFileName) {
        if (hintFileName == null) return "bin";
        int dot = hintFileName.lastIndexOf('.');
        if (dot >= 0 && dot < hintFileName.length() - 1) {
            return hintFileName.substring(dot + 1).toLowerCase();
        }
        return "bin";
    }
}
