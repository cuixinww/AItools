package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.AbstractHandler;
import com.dg.tools.extractor.image.ImageDescriber;
import com.dg.tools.extractor.image.ImageDescription;
import com.dg.tools.extractor.image.NoOpImageDescriber;
import com.dg.tools.extractor.model.*;
import com.dg.tools.extractor.triage.NoOpTriageProcessor;
import com.dg.tools.extractor.triage.RelevanceAssessment;
import com.dg.tools.extractor.triage.TriageProcessor;
import com.dg.tools.extractor.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 递归提取器（RecursiveExtractor）— 三阶段提取架构的编排层。
 *
 * 与 SPEC §3-§6 中的架构一致：
 *   1) Phase 1 UNPACK：调用 handler.unpack() → 递归拆出嵌入文件/图片 → 写源文件 + media/
 *   2) Phase 1.5 TRIAGE+IMAGE：调用 TriageProcessor 过滤无关文档 → ImageDescriber 生成图片描述
 *   3) Phase 2 PARSE：handler.extract() → writeBody + writeChunks + writeLargeTables
 *   4) 写 manifest.json
 *
 * 三阶段均无内存递归：每次只处理一个文件，处理完释放引用后再处理下一个。
 *
 * 对应 SPEC §4 / AGENTS.md §2.3 内存安全原则。
 */
@Service
@Slf4j
public class RecursiveExtractor {

    private final List<AbstractHandler> handlers;
    private final StoreWriter storeWriter;
    private final TypeDetector typeDetector;
    private final TriageProcessor triageProcessor;
    private final ImageDescriber imageDescriber;
    private final int maxDepth;
    private final long maxFileSize;
    private final Path outputBase;

    @Autowired
    public RecursiveExtractor(
            List<AbstractHandler> handlers,
            StoreWriter storeWriter,
            TypeDetector typeDetector,
            @Value("${extractor.pipeline.max-depth:10}") int maxDepth,
            @Value("${extractor.pipeline.max-file-size:209715200}") long maxFileSize,
            @Value("${extractor.output.base:extracted}") String outputBase) {
        this.handlers = handlers;
        this.storeWriter = storeWriter;
        this.typeDetector = typeDetector;
        this.maxDepth = maxDepth;
        this.maxFileSize = maxFileSize;
        this.outputBase = Paths.get(outputBase);
        // Phase 1.5 默认使用空操作实现；接入 LLM 后通过 Spring Bean 注入覆盖
        this.triageProcessor = new NoOpTriageProcessor();
        this.imageDescriber = new NoOpImageDescriber();
    }

    /**
     * 处理根文档（入口方法）。
     * 完成 Phase 1 递归拆包 → Phase 2 逐文件解析 → 写入 manifest.json。
     *
     * @param rawBytes   根文档的原始字节
     * @param fileName   根文档的文件名
     * @param sessionId  提取会话 ID（用于输出目录命名，如未提供则自动生成）
     * @return 输出目录路径
     */
    public Path processRoot(byte[] rawBytes, String fileName, String sessionId) throws IOException {
        if (rawBytes.length > maxFileSize) {
            throw new IOException("File too large: " + fileName
                    + " (" + (rawBytes.length / (1024 * 1024)) + " MB), max is "
                    + (maxFileSize / (1024 * 1024)) + " MB");
        }

        Path sessionDir = outputBase.resolve(sessionId);
        Files.createDirectories(sessionDir);

        Session session = new Session(sessionId, sessionDir);
        String baseName = fileNameToBaseName(fileName);
        String dirName = session.resolveDirName(baseName);

        // Phase 1: 递归拆包
        processFile(rawBytes, fileName, session, 0, null, dirName);

        // Phase 2: 对每个文档做完整解析
        for (DocEntry entry : session.pendingEntries) {
            parseEntry(entry, session);
        }

        // 写 manifest.json
        String manifestJson = StoreWriter.buildManifestJson(sessionId, fileName, session.docInfoList);
        Files.writeString(sessionDir.resolve("manifest.json"), manifestJson);

        log.info("Extraction complete — output: {}", sessionDir.toAbsolutePath());
        return sessionDir;
    }

    /**
     * Phase 1 UNPACK + Phase 1.5 TRIAGE+IMAGE：递归处理一个文件（含其嵌入子文件）。
     */
    private void processFile(byte[] rawBytes, String fileName, Session session,
                              int depth, String parentInfo, String dirName) throws IOException {
        if (depth >= maxDepth) {
            log.warn("Max depth reached for: {}", fileName);
            return;
        }

        // 使用 TypeDetector 识别真实类型（比扩展名更可靠）
        String detectedExt = typeDetector.detectExtension(rawBytes, fileName);
        String sanitizedDirName = StringUtils.sanitizeFileName(dirName);

        Path docDir = session.sessionDir.resolve(sanitizedDirName);
        Files.createDirectories(docDir);

        // 写源文件副本
        storeWriter.writeSourceFile(docDir, rawBytes, fileName);

        // Phase 1 UNPACK
        AbstractHandler handler = resolveHandlerByFileName(fileName);
        if (handler == null) {
            log.warn("No handler for: {} (detected as {})", fileName, detectedExt);
            session.addDocInfo(new StoreWriter.DocInfo(
                    session.nextSeq(), sanitizedDirName, fileName, sanitizedDirName + "/" + fileName,
                    detectedExt, parentInfo, "error"));
            return;
        }

        ExtractionResult unpacked;
        try {
            unpacked = handler.unpack(new ByteArrayInputStream(rawBytes), fileName);
        } catch (Exception e) {
            log.error("Unpack failed for: {}", fileName, e);
            unpacked = ExtractionResult.of(detectedExt, fileName);
            unpacked.addError("unpack: " + e.getMessage());
        }

        // 写 media/ 图片
        try {
            storeWriter.writeMedia(docDir, unpacked);
        } catch (Exception e) {
            log.error("Failed to write media for: {}", fileName, e);
            unpacked.addError("media: " + e.getMessage());
        }

        // Phase 1.5 TRIAGE：相关性快判
        RelevanceAssessment triage = triageProcessor.assess(rawBytes, fileName);
        String status = triage.isRelevant() ? "pending" : "filtered";
        String filterReason = triage.isRelevant() ? null : triage.getReason();
        double filterConfidence = triage.isRelevant() ? 0 : triage.getConfidence();

        // Phase 1.5 IMAGE：图片视觉描述（尝试）
        for (ImageFile img : unpacked.getImages()) {
            try {
                ImageDescription desc = imageDescriber.describe(img.getData(), img.getFormat());
                if (desc != null) {
                    // 写 media/{img}.json（供 Phase 2 图片回填使用）
                    Path imgJson = docDir.resolve("media").resolve(img.getFileName() + ".json");
                    Files.createDirectories(imgJson.getParent());
                    String json = String.format(
                            "{\"title\":\"%s\",\"category\":\"%s\",\"description\":\"%s\"}",
                            escapeJson(desc.getTitle()),
                            escapeJson(desc.getCategory()),
                            escapeJson(desc.getDescription()));
                    Files.writeString(imgJson, json);
                }
            } catch (Exception e) {
                log.debug("Image description skipped for: {}", img.getFileName());
            }
        }

        // 记录 DocInfo
        int seq = session.nextSeq();
        StoreWriter.DocInfo docInfo;
        if ("filtered".equals(status)) {
            docInfo = new StoreWriter.DocInfo(seq, sanitizedDirName, fileName,
                    sanitizedDirName + "/" + fileName, detectedExt, parentInfo, "filtered");
            docInfo.filterReason = filterReason;
            docInfo.filterConfidence = filterConfidence;
        } else {
            docInfo = new StoreWriter.DocInfo(seq, sanitizedDirName, fileName,
                    sanitizedDirName + "/" + fileName, detectedExt, parentInfo, "pending");
        }
        session.addDocInfo(docInfo);

        // 记录待 Phase 2 解析的条目
        session.pendingEntries.add(new DocEntry(
                rawBytes, fileName, sanitizedDirName, detectedExt, parentInfo, seq));

        // 递归处理嵌入文件
        if (!"filtered".equals(status)) {
            for (EmbeddedFile emb : unpacked.getEmbeddedFiles()) {
                String childName = StringUtils.sanitizeFileName(emb.getFileName());
                String childParent = sanitizedDirName + ", pos=" + emb.getPosition();
                String childDirName = session.resolveDirName(
                        fileNameToBaseName(emb.getFileName()));
                processFile(emb.getData(), childName, session, depth + 1, childParent, childDirName);
            }
        }
    }

    /**
     * Phase 2 PARSE：对单个已拆包的文档做完整内容解析并写入产物。
     */
    private void parseEntry(DocEntry entry, Session session) {
        Path docDir = session.sessionDir.resolve(StringUtils.sanitizeFileName(entry.dirName));
        try {
            AbstractHandler handler = resolveHandlerByFileName(entry.fileName);
            if (handler == null) {
                log.warn("No handler for Phase 2: {}", entry.fileName);
                session.updateDocInfoStatus(entry.seq, "error");
                return;
            }

            ExtractionResult parsed = handler.extract(
                    new ByteArrayInputStream(entry.rawBytes), entry.fileName);

            // 写 body.md
            String mdFileName = fileNameToBaseName(entry.fileName) + ".md";
            storeWriter.writeBody(docDir, mdFileName, parsed, entry.seq,
                    entry.fileName, entry.parentInfo);

            // 写 chunks/（标题感知分块）
            storeWriter.writeHierarchicalChunks(docDir, entry.dirName, mdFileName, parsed);

            // 写 data/*.csv + CSV 切片
            storeWriter.writeLargeTables(docDir, parsed);

            // 更新 DocInfo 统计
            session.updateDocInfo(entry.seq, parsed.getElements().size(),
                    parsed.getLargeTables().size(), parsed.getImages().size());

        } catch (Exception e) {
            log.error("Phase 2 parse failed for: {}", entry.fileName, e);
            session.updateDocInfoStatus(entry.seq, "error");
        }
    }

    // ==================== 路由 ====================

    private AbstractHandler resolveHandlerByFileName(String fileName) {
        if (fileName == null || handlers == null) return null;
        for (AbstractHandler h : handlers) {
            if (h.supports(fileName)) return h;
        }
        return null;
    }

    // ==================== 工具方法 ====================

    static String fileNameToBaseName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "unnamed";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ==================== 内部类型 ====================

    /** 提取会话状态。 */
    static class Session {
        final String sessionId;
        final Path sessionDir;
        final AtomicInteger counter = new AtomicInteger(0);
        final Map<String, Integer> dirRegistry = new LinkedHashMap<>();
        final List<StoreWriter.DocInfo> docInfoList = new ArrayList<>();
        final List<DocEntry> pendingEntries = new ArrayList<>();

        Session(String sessionId, Path sessionDir) {
            this.sessionId = sessionId;
            this.sessionDir = sessionDir;
        }

        int nextSeq() { return counter.getAndIncrement(); }

        String resolveDirName(String baseName) {
            String sanitized = StringUtils.sanitizeFileName(baseName);
            int count = dirRegistry.getOrDefault(sanitized, 0);
            dirRegistry.put(sanitized, count + 1);
            if (count == 0) return sanitized;
            return sanitized + "_" + (count + 1);
        }

        void addDocInfo(StoreWriter.DocInfo info) { docInfoList.add(info); }

        void updateDocInfo(int seq, int elementCount, int dataRefCount, int imageCount) {
            for (StoreWriter.DocInfo d : docInfoList) {
                if (d.seq == seq) {
                    d.elementCount = elementCount;
                    d.dataRefCount = dataRefCount;
                    d.imageCount = imageCount;
                    d.status = "done";
                    return;
                }
            }
        }

        void updateDocInfoStatus(int seq, String status) {
            for (StoreWriter.DocInfo d : docInfoList) {
                if (d.seq == seq) {
                    d.status = status;
                    return;
                }
            }
        }
    }

    /** Phase 2 待解析条目。 */
    static class DocEntry {
        final byte[] rawBytes;
        final String fileName;
        final String dirName;
        final String fileType;
        final String parentInfo;
        final int seq;

        DocEntry(byte[] rawBytes, String fileName, String dirName,
                 String fileType, String parentInfo, int seq) {
            this.rawBytes = rawBytes;
            this.fileName = fileName;
            this.dirName = dirName;
            this.fileType = fileType;
            this.parentInfo = parentInfo;
            this.seq = seq;
        }
    }
}
