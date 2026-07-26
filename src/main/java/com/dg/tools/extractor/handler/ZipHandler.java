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
 * ZIP 处理器（ZipHandler）。
 *
 * 负责解压 .zip 文件，把每个条目作为内嵌文件返回（供阶段 1 继续向下递归拆包）。
 *
 * 安全措施：
 *   - 路径穿越防护：拒绝包含 ".." 的条目名，并去除开头的 "/" 与 "./"；
 *   - 压缩炸弹防护：限制单条目大小、总解压大小、条目数量与压缩比；
 *   - 内存隔离：逐条目流式读取，不把整个压缩包一次性载入内存。
 *
 * 对应 SPEC §9 路由表：.zip → ZipHandler。
 */
@Component
@Slf4j
public class ZipHandler extends AbstractHandler {

    private final int maxPerFile;
    private final long maxTotalSize;
    private final int maxCompressionRatio;
    private final int maxEntries;

    public ZipHandler() {
        this.maxPerFile = 100 * 1024 * 1024;
        this.maxTotalSize = 500L * 1024 * 1024;
        this.maxCompressionRatio = 100;
        this.maxEntries = 10_000;
    }

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

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".zip");
    }

    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("zip", fileName);
        extractEntries(is, fileName,
                ef -> unpacked.addEmbedded(ef),
                err -> unpacked.addError(err));
        return unpacked;
    }

    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("zip", fileName);
        extractEntries(is, fileName,
                ef -> result.addEmbedded(ef),
                err -> result.addError(err));
        return result;
    }

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

                String entryName = sanitizeEntryName(entry.getName());
                if (entryName == null) continue;

                if (entryCount++ >= maxEntries) {
                    log.warn("ZipHandler: too many entries in {}, stopping", fileName);
                    break;
                }

                long compressed = entry.getCompressedSize();
                long uncompressed = entry.getSize();
                if (compressed > 0 && uncompressed > 0 && uncompressed / compressed > maxCompressionRatio) {
                    log.warn("ZipHandler: suspicious compression ratio for {}, skipping", entryName);
                    continue;
                }

                byte[] data = readEntry(zis, entryName);
                if (data == null || data.length == 0) {
                    log.warn("ZipHandler: skipping empty or unreadable entry: {}", entryName);
                    continue;
                }

                totalUncompressed += data.length;
                if (totalUncompressed > maxTotalSize) {
                    log.warn("ZipHandler: total size exceeded in {}, stopping", fileName);
                    break;
                }

                addEmbedded.accept(new EmbeddedFile(entryName, position++, data));
            }
            if (!sawAnyEntry) {
                addError.accept("parse error: no entries found");
            }
        } catch (Exception e) {
            log.error("Failed to parse zip: {}", fileName, e);
            addError.accept("parse error: " + e.getMessage());
        }
    }

    private byte[] readEntry(ZipInputStream zis, String entryName) {
        long total = 0;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) != -1) {
                total += n;
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
