package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.EmbeddedFile;
import com.dg.tools.extractor.model.ExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 处理器（.zip）。
 *
 * 负责解压 .zip 文件，把每个条目作为内嵌文件返回（供阶段 1 递归拆包）。
 * <p>安全防护措施：
 * <ul>
 *   <li>路径穿越防护：拒绝包含 ".." 的条目名，去除开头 "/" 和 "./"</li>
 *   <li>压缩炸弹防护：限制单条目大小、总解压大小、最大条目数、压缩比</li>
 *   <li>内存隔离：逐条目流式读取，不把整个压缩包一次性载入内存</li>
 * </ul>
 *
 * @see RecursiveExtractor#processFile
 */
@Component
@Slf4j
public class ZipHandler extends AbstractHandler {

    /** 单个条目的最大解压缩后大小。默认 100 MB。 */
    private final int maxPerFile;

    /** 所有条目的最大总解压大小。默认 500 MB。 */
    private final long maxTotalSize;

    /** 最大压缩比 = 解压大小 / 压缩大小。超过此值判定为可疑压缩炸弹。默认 100。 */
    private final int maxCompressionRatio;

    /** ZIP 文件允许的最大条目总数。超过则停止解压。默认 10000。 */
    private final int maxEntries;

    /** 无参构造函数：使用默认阈值常量。被 Spring 忽略，仅作安全回退。 */
    public ZipHandler() {
        this.maxPerFile = 100 * 1024 * 1024;
        this.maxTotalSize = 500L * 1024 * 1024;
        this.maxCompressionRatio = 100;
        this.maxEntries = 10_000;
    }

    /**
     * 通过 Spring 配置注入阈值参数。
     */
    @Autowired
    public ZipHandler(
            @Value("${extractor.zip.max-per-file:104857600}") int maxPerFile,
            @Value("${extractor.zip.max-total-size:524288000}") long maxTotalSize,
            @Value("${extractor.zip.max-compression-ratio:100}") int maxCompressionRatio,
            @Value("${extractor.zip.max-entries:10000}") int maxEntries) {
        this.maxPerFile = maxPerFile;
        this.maxTotalSize = maxTotalSize;
        this.maxCompressionRatio = maxCompressionRatio;
        this.maxEntries = maxEntries;
    }

    /** 判断当前文件名是否为 .zip 扩展名。 */
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".zip");
    }

    // ==================== Phase 1/2 统一解包逻辑 ====================

    /** Phase 1 拆包：提取所有非空条目作为 EmbeddedFile。 */
    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("zip", fileName);
        extractEntries(is, fileName,
                ef -> unpacked.addEmbedded(ef),
                err -> unpacked.addError(err));
        return unpacked;
    }

    /** Phase 2 解析：同 unpack（ZIP 本身不解析内部文本内容，只做条目拆分）。 */
    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("zip", fileName);
        extractEntries(is, fileName,
                ef -> result.addEmbedded(ef),
                err -> result.addError(err));
        return result;
    }

    /**
     * 核心解压逻辑：流式遍历 ZIP 条目，逐个安全校验后作为 EmbeddedFile 产出。
     * <p>安全检查点：
     * <ol>
     *   <li>目录条目 → 跳过</li>
     *   <li>条目名称含 ".." → 拒绝（路径穿越防护）</li>
     *   <li>条目数超限 → 停止</li>
     *   <li>压缩比异常 → 跳过</li>
     *   <li>单条目大小超限 → 跳过</li>
     *   <li>累计解压总大小超限 → 停止</li>
     * </ol>
     */
    private void extractEntries(InputStream is, String fileName,
                                 Consumer<EmbeddedFile> addEmbedded, Consumer<String> addError) {
        long totalUncompressed = 0;
        int entryCount = 0;
        int position = 0;
        boolean sawAnyEntry = false;

        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                sawAnyEntry = true;
                if (entry.isDirectory()) continue;

                // 清理条目名称：去掉开头 "/" 和 "./"，替换 "\" 为 "/"
                String entryName = sanitizeEntryName(entry.getName());
                if (entryName == null) continue;

                // 条目数量上限防护
                if (entryCount++ >= maxEntries) {
                    log.warn("ZipHandler: too many entries in {}, stopping", fileName);
                    break;
                }

                // 压缩比检查（如果 manifest 中提供了压缩/解压大小信息）
                long compressed = entry.getCompressedSize();
                long uncompressed = entry.getSize();
                if (compressed > 0 && uncompressed > 0 && uncompressed / compressed > maxCompressionRatio) {
                    log.warn("ZipHandler: suspicious compression ratio for {}, skipping", entryName);
                    continue;
                }

                // 流式读取条目数据
                byte[] data = readEntry(zis, entryName);
                if (data == null || data.length == 0) {
                    log.warn("ZipHandler: skipping empty or unreadable entry: {}", entryName);
                    continue;
                }

                // 累计解压大小防护
                totalUncompressed += data.length;
                if (totalUncompressed > maxTotalSize) {
                    log.warn("ZipHandler: total size exceeded in {}, stopping", fileName);
                    break;
                }

                // 添加到结果集
                addEmbedded.accept(new EmbeddedFile(entryName, position++, data));
            }
            // 未找到任何条目可能意味着不是合法的 ZIP 文件
            if (!sawAnyEntry) {
                addError.accept("parse error: no entries found");
            }
        } catch (Exception e) {
            log.error("Failed to parse zip: {}", fileName, e);
            addError.accept("parse error: " + e.getMessage());
        }
    }

    /**
     * 流式读取 ZIP 条目内容到 byte[]，同时检查单条目大小。
     * @return 条目字节数据；读取失败或超大时返回 null
     */
    private byte[] readEntry(ZipInputStream zis, String entryName) {
        long total = 0;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) {
                total += n;
                // 单条目大小超限 → 停止读取并丢弃
                if (total > maxPerFile) {
                    log.warn("ZipHandler: entry too large: {}, skipping", entryName);
                    return null;
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("ZipHandler: failed to read entry: {}: {}", entryName, e.getMessage());
            return null;
        }
    }

    /**
     * 清理 ZIP 条目名称：
     * <ol>
     *   <li>将反斜杠统一为正斜杠（Windows 兼容）</li>
     *   <li>拒绝包含 ".." 的路径（防止路径穿越攻击）</li>
     *   <li>去除开头 "/" 和 "./"（消除根目录引用）</li>
     * </ol>
     * @return 清理后的名称；非法或空则返回 null
     */
    private String sanitizeEntryName(String name) {
        if (name == null || name.isEmpty()) return null;
        name = name.replace('\\', '/');
        if (name.contains("..")) {
            log.warn("ZipHandler: rejecting path traversal entry: {}", name);
            return null;
        }
        while (name.startsWith("/") || name.startsWith("./")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? null : name;
    }
}
