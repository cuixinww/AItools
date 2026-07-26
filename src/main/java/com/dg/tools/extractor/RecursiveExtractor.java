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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 递归提取器 — 三阶段提取架构的编排层。
 *
 * 完整流程：
 * <ol>
 *   <li><b>Phase 1 UNPACK</b>：调用 handler.unpack() → 递归拆出嵌入文件/图片 → 写源文件副本 + media/</li>
 *   <li><b>Phase 1.5 TRIAGE+IMAGE</b>：调用 TriageProcessor 过滤无关文档 → ImageDescriber 生成图片描述 JSON</li>
 *   <li><b>Phase 2 PARSE</b>：逐文件调用 handler.extract() → writeBody + writeHierarchicalChunks + writeLargeTables</li>
 *   <li><b>写入 manifest.json</b></li>
 * </ol>
 *
 * <p>内存隔离保证：ProcessFile 使用 byte[] 而非 InputStream，
 * 因为递归场景下无法对 InputStream 做 seek 操作。
 * 但通过 DocEntry.release() 在 Phase 2 完成后释放引用，
 * 确保每次只在一个线程中持有一个文件的字节数组。</p>
 *
 * @see RecursiveExtractor.Session
 * @see RecursiveExtractor.DocEntry
 */
@Service
@Slf4j
public class RecursiveExtractor {

    /** Handler 路由表，由 Spring 自动注入所有 @Component Handler。 */
    private final List<AbstractHandler> handlers;

    /** 产物写出器（body.md / chunks/ / data/ / media/）。 */
    private final StoreWriter storeWriter;

    /** 类型探测器（Tika-based MIME 识别）。 */
    private final TypeDetector typeDetector;

    /** TRIAGE 处理器：判定嵌入文档是否相关（可注入 LLM 实现）。 */
    private final TriageProcessor triageProcessor;

    /** IMAGE 处理器：生成图片视觉描述 JSON（可注入视觉模型实现）。 */
    private final ImageDescriber imageDescriber;

    /** 最大递归深度。默认 10 层。 */
    private final int maxDepth;

    /** 单文件最大字节数。默认 209,715,200 (200 MB)。 */
    private final long maxFileSize;

    /** 输出根目录路径。 */
    private final Path outputBase;

    /**
     * 通过 Spring 配置初始化 RecursiveExtractor。
     * triageProcessor 和 imageDescriber 由 Spring Bean 注入，
     * 若项目中未注册则回退为 NoOp 默认实现。
     */
    @Autowired
    public RecursiveExtractor(
            List<AbstractHandler> handlers,
            StoreWriter storeWriter,
            TypeDetector typeDetector,
            @Autowired(required = false) TriageProcessor triageProcessor,
            @Autowired(required = false) ImageDescriber imageDescriber,
            @Value("${extractor.pipeline.max-depth:10}") int maxDepth,
            @Value("${extractor.pipeline.max-file-size:209715200}") long maxFileSize,
            @Value("${extractor.output.base:extracted}") String outputBase) {
        this.handlers = handlers;
        this.storeWriter = storeWriter;
        this.typeDetector = typeDetector;
        this.triageProcessor = triageProcessor != null ? triageProcessor : new NoOpTriageProcessor();
        this.imageDescriber = imageDescriber != null ? imageDescriber : new NoOpImageDescriber();
        this.maxDepth = maxDepth;
        this.maxFileSize = maxFileSize;
        this.outputBase = Paths.get(outputBase);
    }

    /**
     * 批量处理多个根文件。
     * 所有根文件共享同一个 UUID 输出目录，各自以文件名（去扩展名）创建子目录。
     * <p>适用场景：
     * <ul>
     *   <li>单文件：传入一个 FileEntry 即可</li>
     *   <li>多文件：FS + UR 文档对同时提取</li>
     *   <li>目录扫描：Application CLI 扫描目录后批量传入</li>
     * </ul>
     *
     * @param rootFiles 根文件列表
     * @return 输出目录（UUID）路径
     */
    public Path processRootFiles(List<FileEntry> rootFiles) throws IOException {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        Path sessionDir = outputBase.resolve(sessionId);
        Files.createDirectories(sessionDir);

        Session session = new Session(sessionId, sessionDir);

        // Phase 1: 所有根文件递归拆包
        for (FileEntry f : rootFiles) {
            if (f.rawBytes.length > maxFileSize) {
                log.warn("File too large, skipping: {} ({} MB)", f.fileName,
                        f.rawBytes.length / (1024 * 1024));
                continue;
            }
            String baseName = fileNameToBaseName(f.fileName);
            String dirName = session.resolveDirName(baseName);
            processFile(f.rawBytes, f.fileName, session, 0, null, dirName);
        }

        // Phase 2: 逐文件解析
        for (DocEntry entry : session.pendingEntries) {
            parseEntry(entry, session);
        }

        // 修正 parent 引用
        session.fixupParentPositions();

        // 写 manifest.json
        String rootDesc = rootFiles.size() == 1 ? rootFiles.get(0).fileName
                : rootFiles.size() + " files";
        String manifestJson = StoreWriter.buildManifestJson(sessionId, rootDesc, session.docInfoList);
        Files.writeString(sessionDir.resolve("manifest.json"), manifestJson);

        log.info("Extraction complete — {} root files → output: {}", rootFiles.size(),
                sessionDir.toAbsolutePath());
        return sessionDir;
    }

    /**
     * 单个文件提取（向后兼容）。
     * 内部转为 processRootFiles 调用。
     */
    public Path processRoot(byte[] rawBytes, String fileName, String sessionId) throws IOException {
        return processRootFiles(List.of(new FileEntry(rawBytes, fileName)));
    }

    /** 根文件条目。 */
    public record FileEntry(byte[] rawBytes, String fileName) {}

    /**
     * Phase 1 UNPACK + Phase 1.5 TRIAGE+IMAGE：递归处理一个文件（含其嵌入子文件）。
     * <p>处理流程：
     * <ol>
     *   <li>检查 depth 和文件大小上限 → 写源文件副本到 docDir</li>
     *   <li>TypeDetector 识别真实类型 → 找到对应 Handler → unpack()</li>
     *   <li>写 media/ 图片到 disk</li>
     *   <li>TriageProcessor 判定相关性 → filtered 则跳过后续处理</li>
     *   <li>ImageDescriber 生成图片描述 JSON</li>
     *   <li>记录 DocInfo + DocEntry（待 Phase 2）</li>
     *   <li>若非 filtered → 递归处理 unpack 返回的 EmbeddedFile</li>
     * </ol>
     */
    private void processFile(byte[] rawBytes, String fileName, Session session,
                              int depth, String parentInfo, String dirName) throws IOException {
        // 递归深度保护
        if (depth >= maxDepth) {
            log.warn("Max depth reached for: {}", fileName);
            return;
        }

        // 单个文件大小上限检查（防止嵌入文件过大导致 OOM）
        if (rawBytes.length > maxFileSize) {
            log.warn("Embedded file too large: {} ({} MB), skipping",
                    fileName, rawBytes.length / (1024 * 1024));
            return;
        }

        // 使用 TypeDetector 识别真实类型（比扩展名更可靠）
        String detectedExt = typeDetector.detectExtension(rawBytes, fileName);
        String sanitizedDirName = StringUtils.sanitizeFileName(dirName);

        // 创建文档专属输出目录
        Path docDir = session.sessionDir.resolve(sanitizedDirName);
        Files.createDirectories(docDir);

        // 写源文件副本（200MB 以内的文件）
        try {
            storeWriter.writeSourceFile(docDir, rawBytes, fileName);
        } catch (IOException e) {
            log.warn("Failed to write source file {}: {}", fileName, e.getMessage());
            // 不中断流程，继续尝试解析
        }

        // Phase 1 UNPACK：使用文件名路由 Handler（因为 unpack 依赖扩展名判断）
        AbstractHandler handler = resolveHandlerByFileName(fileName);
        if (handler == null) {
            log.warn("No handler for: {} (detected as {})", fileName, detectedExt);
            session.addDocInfo(new StoreWriter.DocInfo(
                    session.nextSeq(), sanitizedDirName, fileName, sanitizedDirName + "/" + fileName,
                    detectedExt, parentInfo, "error"));
            return;
        }

        // 调用 Handler.unpack() — 捕获异常避免阻断整个流程
        ExtractionResult unpacked;
        try {
            unpacked = handler.unpack(new ByteArrayInputStream(rawBytes), fileName);
        } catch (Exception e) {
            log.error("Unpack failed for: {}", fileName, e);
            unpacked = ExtractionResult.of(detectedExt, fileName);
            unpacked.addError("unpack: " + e.getMessage());
        }

        // 写 media/ 图片到磁盘
        try {
            storeWriter.writeMedia(docDir, unpacked);
        } catch (Exception e) {
            log.error("Failed to write media for: {}", fileName, e);
            unpacked.addError("media: " + e.getMessage());
        }

        // ========== Phase 1.5 TRIAGE：相关性快判 ==========
        RelevanceAssessment triage = triageProcessor.assess(rawBytes, fileName);
        String status = triage.isRelevant() ? "pending" : "filtered";
        String filterReason = triage.isRelevant() ? null : triage.getReason();
        double filterConfidence = triage.isRelevant() ? 0 : triage.getConfidence();

        // ========== Phase 1.5 IMAGE：图片视觉描述 ==========
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

        // 记录 DocInfo（进入待解析队列或标记为 filtered）
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

        // 记录待 Phase 2 解析的条目（尚未解析，等 Phase 1 全部完成再批量处理）
        session.pendingEntries.add(new DocEntry(
                rawBytes, fileName, sanitizedDirName, detectedExt, parentInfo, seq));

        // 递归处理内嵌文件（若被 triage 过滤则跳过）
        if (!"filtered".equals(status)) {
            for (EmbeddedFile emb : unpacked.getEmbeddedFiles()) {
                byte[] childData = emb.getData();
                String childFileName = emb.getFileName();
                String childExt = typeDetector.detectExtension(childData, childFileName);
                String childName = StringUtils.sanitizeFileName(childFileName);
                String childParent = sanitizedDirName + ", pos=" + emb.getPosition();
                String childDirName = session.resolveDirName(
                        fileNameToBaseName(childFileName));
                processFile(childData, childName, session, depth + 1, childParent, childDirName);
            }
        }
    }

    /**
     * Phase 2 PARSE：对单个已拆包的文档做完整内容解析并写入产物。
     * <p>处理流程：resolveHandler → extract() → writeBody → writeChunks → writeLargeTables
     * <p>解析完成后在 finally 块中释放 rawBytes 引用，允许 GC 回收。
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

            // 执行 Phase 2 完整解析
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

            // 更新 DocInfo 统计（elementCount/dataRefCount/imageCount/status="done"）
            session.updateDocInfo(entry.seq, parsed.getElements().size(),
                    parsed.getLargeTables().size(), parsed.getImages().size());

            // 缓存 embed Element 位置映射（供 fixupParentPositions 使用）
            cacheEmbedElementPositions(entry.seq, parsed, session);

        } catch (Exception e) {
            log.error("Phase 2 parse failed for: {}", entry.fileName, e);
            session.updateDocInfoStatus(entry.seq, "error");
        } finally {
            // 释放 rawBytes 引用，允许 GC 回收该文件的字节数据
            entry.release();
        }
    }

    /**
     * 缓存父文档中 embed 位置映射（供 fixupParentPositions 使用）。
     */
    private void cacheEmbedElementPositions(int seq, ExtractionResult parsed, Session session) {
        StoreWriter.DocInfo docInfo = null;
        for (StoreWriter.DocInfo d : session.docInfoList) {
            if (d.seq == seq) { docInfo = d; break; }
        }
        if (docInfo == null) return;
        docInfo.embedElementPositions = new LinkedHashMap<>();
        for (EmbeddedFile emb : parsed.getEmbeddedFiles()) {
            docInfo.embedElementPositions.put(emb.getPosition(), emb.getPosition());
        }
    }

    // ==================== 路由 ====================

    /**
     * 根据文件名从 handlers 列表中查找匹配的 Handler。
     */
    private AbstractHandler resolveHandlerByFileName(String fileName) {
        if (fileName == null || handlers == null) return null;
        for (AbstractHandler h : handlers) {
            if (h.supports(fileName)) return h;
        }
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 从文件名中去除扩展名得到基本名称。
     * 例如 "report.docx" → "report"；"no-ext" → "no-ext"。
     */
    static String fileNameToBaseName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "unnamed";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * JSON 字符串转义：将特殊字符转换为对应的转义序列，
     * 用于构建内联 JSON（如图片描述文件）。
     */
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

    /**
     * 提取会话状态。
     * 封装一个 session 内的所有元数据和中间状态，
     * 包括 docInfoList（清单条目）、pendingEntries（Phase 2 待解析队列）、
     * dirRegistry（目录名去重计数）。
     */
    static class Session {
        /** 会话 ID。 */
        final String sessionId;
        /** 会话输出根目录。 */
        final Path sessionDir;
        /** 全局递增序列号计数器（每个 DocEntry/DocInfo 分配唯一 seq）。 */
        final AtomicInteger counter = new AtomicInteger(0);
        /** 目录名注册表：sanitizedName → 出现次数（用于生成 _2, _3 后缀）。 */
        final Map<String, Integer> dirRegistry = new LinkedHashMap<>();
        /** 所有文档的 DocInfo 列表（用于 manifest.json）。 */
        final List<StoreWriter.DocInfo> docInfoList = new ArrayList<>();
        /** Phase 1 完成后待 Phase 2 解析的条目队列。 */
        final List<DocEntry> pendingEntries = new ArrayList<>();

        Session(String sessionId, Path sessionDir) {
            this.sessionId = sessionId;
            this.sessionDir = sessionDir;
        }

        /** 获取下一个全局序列号。 */
        int nextSeq() { return counter.getAndIncrement(); }

        /**
         * 解析目录名：同名的第二个实例自动加 _2，第三个加 _3，以此类推。
         */
        String resolveDirName(String baseName) {
            String sanitized = StringUtils.sanitizeFileName(baseName);
            int count = dirRegistry.getOrDefault(sanitized, 0);
            dirRegistry.put(sanitized, count + 1);
            if (count == 0) return sanitized;
            return sanitized + "_" + (count + 1);
        }

        /** 添加一个文档元信息到清单列表。 */
        void addDocInfo(StoreWriter.DocInfo info) { docInfoList.add(info); }

        /**
         * Phase 2 完成后更新 DocInfo：回填 elementCount / dataRefCount / imageCount，标记 status="done"。
         */
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

        /** 更新指定 seq 的 DocInfo 状态（通常为 "error"）。 */
        void updateDocInfoStatus(int seq, String status) {
            for (StoreWriter.DocInfo d : docInfoList) {
                if (d.seq == seq) {
                    d.status = status;
                    return;
                }
            }
        }

        /**
         * Phase 2 完成后修正 parent 引用：将 unpack 阶段的 EmbeddedFile.position
         * 替换为 Phase 2 解析后的 Element.position。
         * parent 格式从 "dirName, pos=N" 变为 "dirName, element_pos=N"。
         */
        void fixupParentPositions() {
            for (StoreWriter.DocInfo d : docInfoList) {
                if (d.parentInfo == null) continue;
                String[] parts = d.parentInfo.split(", pos=", 2);
                if (parts.length != 2) continue;
                String parentDir = parts[0];
                int unpackPos;
                try {
                    unpackPos = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                // 在父文档的 elements 中查找该位置附近的 embed Element
                StoreWriter.DocInfo parent = findDocByDir(parentDir);
                if (parent == null || parent.embedElementPositions == null) continue;
                // 使用父文档缓存的 embed Element 位置映射
                Integer elementPos = parent.embedElementPositions.get(unpackPos);
                if (elementPos != null) {
                    d.parentInfo = parentDir + ", element_pos=" + elementPos;
                }
            }
        }

        private StoreWriter.DocInfo findDocByDir(String dir) {
            for (StoreWriter.DocInfo d : docInfoList) {
                if (dir.equals(d.dir)) return d;
            }
            return null;
        }
    }

    /**
     * Phase 2 待解析条目。
     * rawBytes 在 Phase 2 完成后通过 release() 释放，允许 GC 回收。
     */
    static class DocEntry {
        /** 文档原始字节数据，Phase 2 完成后设为 null 以释放内存。 */
        byte[] rawBytes;
        /** 文件名（已清洗过的）。 */
        final String fileName;
        /** 文档输出目录名。 */
        final String dirName;
        /** 检测到的文件类型。 */
        final String fileType;
        /** 父级引用描述，如 "dirName, pos=3"。 */
        final String parentInfo;
        /** 会话内全局唯一序列号。 */
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

        /** 释放 rawBytes 引用，允许 GC 回收该文件的字节数据。 */
        void release() {
            this.rawBytes = null;
        }
    }
}
