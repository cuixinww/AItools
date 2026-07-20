package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.EmbeddedFile;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.UnpackResult;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 处理器（ZipHandler）。
 *
 * 负责解压 .zip 文件，把每个条目作为内嵌文件返回（供阶段 1 继续向下递归拆包）。
 *
 * 安全措施：
 *   - 路径穿越防护：拒绝包含 ".." 的条目名，并去除开头的 "/" 与 "./"；
 *   - 压缩炸弹防护：限制单条目大小、总解压大小、条目数量与压缩比；
 *   - 内存隔离：逐条目流式读取，不把整个压缩包一次性载入内存。
 *
 * 由于覆盖了默认的 unpack(...) 实现，这里直接把条目以内嵌文件形式返回，
 * 不会走 DocumentHandler 默认的「先 extract 再丢文本」路径。
 */
@Component
public class ZipHandler implements DocumentHandler {

    /** 单条目解压上限（100 MB）。 */
    private static final int MAX_PER_FILE = 100 * 1024 * 1024;

    /** 整个压缩包解压后总大小上限（500 MB）。 */
    private static final long MAX_TOTAL_SIZE = 500L * 1024 * 1024;

    /** 压缩比阈值（解压/压缩 > 此值视为可疑压缩炸弹）。 */
    private static final int MAX_COMPRESSION_RATIO = 100;

    /** 条目数量上限（防止 zip 炸弹通过海量小文件拖垮内存）。 */
    private static final int MAX_ENTRIES = 10_000;

    /**
     * 是否支持该文件：匹配 .zip。
     */
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".zip");
    }

    /**
     * 阶段 1 拆包：把压缩包内每个条目作为内嵌文件返回。
     */
    @Override
    public UnpackResult unpack(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        UnpackResult unpacked = new UnpackResult("zip", fileName);
        extractEntries(is, fileName, unpacked, -1);
        return unpacked;
    }

    /**
     * 阶段 2 解析：与拆包逻辑相同（zip 本身没有「正文」可解析，只暴露条目）。
     */
    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("zip", fileName);
        extractEntries(is, fileName, result, -1);
        return result;
    }

    /**
     * 阶段 1 / 阶段 2 共用的条目抽取逻辑。
     * 当 addTo 为 ExtractionResult 时，条目作为内嵌文件（带 position）加入；
     * 当 addTo 为 UnpackResult 时，条目同样作为内嵌文件加入。
     * 默认 unpack 实现已被本类覆盖，因此这里不会走到父类的「extract 后再丢文本」路径。
     *
     * @param is       压缩包输入流
     * @param fileName 压缩包文件名（仅用于日志）
     * @param addTo    结果载体（ExtractionResult 或 UnpackResult）
     * @param maxDepth 预留的递归深度参数（当前未用于 zip 内条目递归）
     */
    private void extractEntries(InputStream is, String fileName, Object addTo, int maxDepth) {
        long totalUncompressed = 0;
        int entryCount = 0;
        int position = 0;

        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                // 路径穿越防护
                String entryName = sanitizeEntryName(entry.getName());
                if (entryName == null) continue;

                // 条目数量上限
                if (entryCount++ >= MAX_ENTRIES) {
                    System.err.println("ZipHandler: too many entries in " + fileName + ", stopping");
                    break;
                }

                long compressed = entry.getCompressedSize();
                long uncompressed = entry.getSize();
                // 压缩炸弹检测：极端压缩比直接跳过该条目
                if (compressed > 0 && uncompressed > 0 && uncompressed / compressed > MAX_COMPRESSION_RATIO) {
                    System.err.println("ZipHandler: suspicious compression ratio for " + entryName + ", skipping");
                    continue;
                }

                byte[] data = readEntry(zis, entryName);
                if (data == null) continue;

                totalUncompressed += data.length;
                // 总解压大小上限
                if (totalUncompressed > MAX_TOTAL_SIZE) {
                    System.err.println("ZipHandler: total size exceeded in " + fileName + ", stopping");
                    break;
                }

                if (addTo instanceof ExtractionResult er) {
                    er.addEmbedded(new EmbeddedFile(entryName, position++, data));
                } else if (addTo instanceof UnpackResult ur) {
                    ur.addEmbedded(new EmbeddedFile(entryName, position++, data));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse zip: " + fileName, e);
        }
    }

    /** 流式读取单个条目，超过单条目上限则跳过并返回 null。 */
    private byte[] readEntry(ZipInputStream zis, String entryName) {
        long total = 0;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) {
                total += n;
                if (total > MAX_PER_FILE) {
                    System.err.println("ZipHandler: entry too large: " + entryName + ", skipping");
                    return null;
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("ZipHandler: failed to read entry: " + entryName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 拒绝含路径穿越的条目名，返回清洗后的名称；不安全则返回 null（跳过）。
     * 清洗规则：统一分隔符为 "/"，拒绝包含 ".."，去除开头 "/" 与 "./"。
     */
    private static String sanitizeEntryName(String name) {
        if (name == null || name.isEmpty()) return null;
        // 统一路径分隔符
        name = name.replace('\\', '/');
        // 拒绝路径穿越
        if (name.contains("..")) {
            System.err.println("ZipHandler: rejecting path traversal entry: " + name);
            return null;
        }
        // 去除前导 "/" 与 "./"
        while (name.startsWith("/") || name.startsWith("./")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? null : name;
    }
}
