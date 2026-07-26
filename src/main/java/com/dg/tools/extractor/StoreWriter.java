package com.dg.tools.extractor;

import com.dg.tools.extractor.excel.CsvSlicer;
import com.dg.tools.extractor.model.*;
import com.dg.tools.extractor.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化产物写出器。
 *
 * 负责把解析结果 {@link ExtractionResult} 落盘为以下产物：
 * <ul>
 *   <li>body.md — 正文 Markdown（含 # POS / TYPE 头、大表 data_ref、图片引用）</li>
 *   <li>chunks/ — 标题感知分块 + index.json（供 LLM 按需加载）</li>
 *   <li>data/   — 大表的完整 CSV + 必要时切片（&gt;500 行时）</li>
 *   <li>media/  — 图片原始文件</li>
 *   <li>源文件  — 原始二进制副本（在 RecursiveExtractor 阶段 1 写出）</li>
 *   <li>manifest.json — 会话级清单（由 buildManifestJson 生成）</li>
 * </ul>
 *
 * <p>无状态工具类（除静态常量与共享 ObjectMapper 外），可安全复用。</p>
 *
 * @see StoreWriter.DocInfo
 * @see CsvSlicer
 */
@Slf4j
public class StoreWriter {

    /** 单个 chunk 包含的元素数量（默认 50，仅用于回退分块）。 */
    private static final int CHUNK_SIZE = 50;

    /** Jackson JSON 序列化器（线程安全，可复用）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 源文件副本写入磁盘的大小上限（200 MB）。 */
    private static final int MAX_SOURCE_FILE_SIZE = 200 * 1024 * 1024;

    /**
     * 写出正文 body.md。
     * 格式：每行以 "# POS: N | TYPE: xxx | metadata" 开头，后跟元素正文内容。
     */
    public void writeBody(Path docDir, String mdFileName, ExtractionResult result,
                          int seq, String sourceFile, String parentInfo) throws IOException {
        Files.createDirectories(docDir);
        Path bodyFile = docDir.resolve(mdFileName);

        try (BufferedWriter bw = Files.newBufferedWriter(bodyFile, StandardCharsets.UTF_8)) {
            // 文档头部元信息
            bw.write("# DOC: " + seq); bw.newLine();
            bw.write("# SOURCE: " + sourceFile); bw.newLine();
            if (parentInfo != null) {
                bw.write("# PARENT: " + parentInfo); bw.newLine();
            }
            bw.newLine();

            // 写出所有正文元素（paragraph / table / header / footer 等）
            for (Element elem : result.getElements()) {
                writeElement(bw, elem);
            }

            // 写出大表 data_ref 引用（含 schema、行数预览）
            for (LargeTableInfo lt : result.getLargeTables()) {
                bw.write("# POS: " + lt.getPosition() + " | TYPE: data_ref | " +
                        "schema: " + lt.getSchema() + " | rows: " + lt.getRowCount() +
                         " | file: data/" + StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv");
                bw.newLine();
                // 输出 preview 文本（以 "> " 前缀的行内注释）
                if (lt.getPreview() != null && !lt.getPreview().isEmpty()) {
                    for (String line : lt.getPreview().split("\\r?\\n")) {
                        bw.write("> " + line);
                        bw.newLine();
                    }
                }
                bw.newLine();
            }

            // 写出图片引用
            for (ImageFile img : result.getImages()) {
                bw.write("# POS: " + img.getPosition() + " | TYPE: image | " +
                        "file: media/" + StringUtils.sanitizeFileName(img.getFileName()));
                bw.newLine();
                bw.newLine();
            }
        }
    }

    /**
     * 写出 chunks/ 目录：固定大小分块（向后兼容旧测试）。
     * 新代码应使用 {@link #writeHierarchicalChunks}。
     */
    @Deprecated
    public void writeChunks(Path docDir, String docDirName, String mdFileName, ExtractionResult result) throws IOException {
        int totalElements = result.getElements().size();
        int totalLargeTables = result.getLargeTables().size();
        int totalImages = result.getImages().size();

        Path chunksDir = docDir.resolve("chunks");
        Files.createDirectories(chunksDir);

        int totalChunks = (totalElements + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (totalChunks == 0) totalChunks = 1;

        // 构建 index.json 内容
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("source", docDirName + "/" + mdFileName);
        index.put("total_elements", totalElements);
        index.put("total_large_tables", totalLargeTables);
        index.put("total_images", totalImages);
        index.put("chunk_size", CHUNK_SIZE);

        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int endExclusive = Math.min(start + CHUNK_SIZE, totalElements);
            int endInclusive = endExclusive - 1;
            String chunkFile = String.format("chunk_%04d.md", i + 1);
            boolean hasLargeTable = result.getLargeTables().stream()
                    .anyMatch(lt -> lt.getPosition() >= start && lt.getPosition() < endExclusive);
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("file", chunkFile);
            chunk.put("pos_range", List.of(start, endInclusive));
            chunk.put("element_count", endExclusive - start);
            chunk.put("includes_large_table", hasLargeTable);
            chunks.add(chunk);
        }
        index.put("chunks", chunks);

        // 列出各 chunk 中包含的大表
        List<Map<String, Object>> largeTables = new ArrayList<>();
        for (LargeTableInfo lt : result.getLargeTables()) {
            String csvFile = StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv";
            int chunkIdx = lt.getPosition() / CHUNK_SIZE;
            Map<String, Object> tbl = new LinkedHashMap<>();
            tbl.put("name", csvFile);
            tbl.put("sheet", lt.getSheetName());
            tbl.put("pos", lt.getPosition());
            tbl.put("rows", lt.getRowCount());
            tbl.put("file", "data/" + csvFile);
            tbl.put("in_chunk", String.format("chunk_%04d.md", chunkIdx + 1));
            largeTables.add(tbl);
        }
        index.put("large_tables", largeTables);

        Files.writeString(chunksDir.resolve("index.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                StandardCharsets.UTF_8);

        // 逐 chunk 写出
        List<Element> elements = result.getElements();
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, elements.size());
            String chunkFile = String.format("chunk_%04d.md", i + 1);
            try (BufferedWriter bw = Files.newBufferedWriter(
                    chunksDir.resolve(chunkFile), StandardCharsets.UTF_8)) {
                bw.write("# Chunk " + (i + 1) + " of " + totalChunks); bw.newLine();
                bw.write("# Source: " + docDirName + "/" + mdFileName); bw.newLine();
                bw.write("# Pos range: " + start + " - " + (end - 1)); bw.newLine();
                bw.newLine();
                for (int j = start; j < end; j++) {
                    writeElement(bw, elements.get(j));
                }
            }
        }
    }

    /**
     * 标题感知分块（新 API）—— 基于 token 数而非元素数。
     * <p>分块策略：
     * <ol>
     *   <li>全文档内容 token ≤ MAX_TOKENS_WHOLE_DOC(3000) → 不分块</li>
     *   <li>有标题 → 按 H1→H2→H3 层级逐级切分，每块 ≤ MAX_TOKENS_PER_CHUNK(2000) token</li>
     *   <li>无标题 → 按 token 数回退到固定大小分块</li>
     * </ol>
     * <p>中文 token 估算：中文约 ~1.5 char/token，英文 ~4 char/token，取 ~2.5 char/token 作为折中。</p>
     */
    public void writeHierarchicalChunks(Path docDir, String docDirName, String mdFileName,
                                         ExtractionResult result) throws IOException {
        int totalElements = result.getElements().size();
        int totalLargeTables = result.getLargeTables().size();
        int totalImages = result.getImages().size();

        Path chunksDir = docDir.resolve("chunks");
        Files.createDirectories(chunksDir);

        List<Element> elements = result.getElements();

        // 用 token 数判断是否不分块（3000 token 阈值，约 ~7500 字符中文或 ~12000 字符英文）
        int totalTokens = estimateTokens(elements, 0, Math.max(0, totalElements - 1));
        if (totalTokens <= MAX_TOKENS_WHOLE_DOC) {
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("source", docDirName + "/" + mdFileName);
            index.put("total_elements", totalElements);
            index.put("total_large_tables", totalLargeTables);
            index.put("total_images", totalImages);
            index.put("chunk_size", 0);
            index.put("chunk_mode", "none");
            index.put("approximate_tokens", totalTokens);
            index.put("chunks", List.of());
            Files.writeString(chunksDir.resolve("index.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                    StandardCharsets.UTF_8);
            return;
        }

        // 计算分块边界（标题感知 + token 上限）
        List<ChunkBoundary> boundaries = computeHierarchicalChunks(elements);
        int totalChunks = boundaries.size();
        if (totalChunks == 0) {
            // 无标题 → 按 token 数回退到固定分块
            boundaries = computeTokenBasedChunks(elements, MAX_TOKENS_PER_CHUNK);
            totalChunks = boundaries.size();
        }

        // 构建 index.json
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("source", docDirName + "/" + mdFileName);
        index.put("total_elements", totalElements);
        index.put("total_large_tables", totalLargeTables);
        index.put("total_images", totalImages);
        index.put("chunk_size", 0);
        index.put("chunk_mode", totalChunks > 0 && boundaries.get(0).headingLevel > 0 ? "heading" : "token");
        index.put("approximate_tokens", totalTokens);

        List<Map<String, Object>> chunkList = new ArrayList<>();
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundary b = boundaries.get(i);
            String chunkFile = String.format("chunk_%04d.md", i + 1);
            boolean hasLargeTable = result.getLargeTables().stream()
                    .anyMatch(lt -> lt.getPosition() >= b.start && lt.getPosition() <= b.end);
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("file", chunkFile);
            chunk.put("pos_range", List.of(b.start, b.end));
            chunk.put("element_count", b.end - b.start + 1);
            chunk.put("approximate_tokens", estimateTokens(elements, b.start, b.end));
            if (b.headingLevel > 0) {
                chunk.put("heading_level", b.headingLevel);
                chunk.put("heading_text", b.headingText);
            }
            chunk.put("includes_large_table", hasLargeTable);
            chunkList.add(chunk);
        }
        index.put("chunks", chunkList);

        // 列出每个大表所在的 chunk
        List<Map<String, Object>> largeTables = new ArrayList<>();
        for (LargeTableInfo lt : result.getLargeTables()) {
            String csvFile = StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv";
            int chunkIdx = findChunkIndex(boundaries, lt.getPosition());
            Map<String, Object> tbl = new LinkedHashMap<>();
            tbl.put("name", csvFile);
            tbl.put("sheet", lt.getSheetName());
            tbl.put("pos", lt.getPosition());
            tbl.put("rows", lt.getRowCount());
            tbl.put("file", "data/" + csvFile);
            if (chunkIdx >= 0) {
                tbl.put("in_chunk", String.format("chunk_%04d.md", chunkIdx + 1));
            }
            largeTables.add(tbl);
        }
        index.put("large_tables", largeTables);

        Files.writeString(chunksDir.resolve("index.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                StandardCharsets.UTF_8);

        // 逐 chunk 写出文件
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundary b = boundaries.get(i);
            String chunkFile = String.format("chunk_%04d.md", i + 1);
            try (BufferedWriter bw = Files.newBufferedWriter(
                    chunksDir.resolve(chunkFile), StandardCharsets.UTF_8)) {
                bw.write("# Chunk " + (i + 1) + " of " + boundaries.size()); bw.newLine();
                bw.write("# Source: " + docDirName + "/" + mdFileName); bw.newLine();
                bw.write("# Pos range: " + b.start + " - " + b.end); bw.newLine();
                if (b.headingLevel > 0) {
                    bw.write("# Section: " + b.headingText); bw.newLine();
                }
                bw.newLine();
                for (int j = b.start; j <= b.end && j < elements.size(); j++) {
                    writeElement(bw, elements.get(j));
                }
            }
        }
    }

    /** 不分块的 token 上限（约相当于 223 个中文元素的文档全文）。 */
    private static final int MAX_TOKENS_WHOLE_DOC = 3000;

    /** 单块最大 token 数。 */
    private static final int MAX_TOKENS_PER_CHUNK = 2000;

    /** 单块最小 token 数（避免碎片 chunk）。 */
    private static final int MIN_TOKENS_PER_CHUNK = 200;

    /**
     * 计算标题感知的分块边界。
     * <p>算法：
     * <ol>
     *   <li>收集所有 headingLevel 1-3 的元素位置</li>
     *   <li>相邻标题之间为一节（H1 粒度）</li>
     *   <li>单节超过 200 元素且存在子标题（hLevel > current）→ 按子标题细切</li>
     * </ol>
     */
    static List<ChunkBoundary> computeHierarchicalChunks(List<Element> elements) {
        List<ChunkBoundary> result = new ArrayList<>();
        if (elements.isEmpty()) return result;

        // 第一遍：收集所有 H1-H3 标题的位置
        List<Integer> headingPositions = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            if (e.getHeadingLevel() != null && e.getHeadingLevel() >= 1 && e.getHeadingLevel() <= 3) {
                headingPositions.add(i);
            }
        }
        // 没有任何标题 → 返回空列表，上层会回退到固定分块
        if (headingPositions.isEmpty()) return result;

        // 第二遍：按标题分组
        for (int hi = 0; hi < headingPositions.size(); hi++) {
            int start = headingPositions.get(hi);
            int end = (hi + 1 < headingPositions.size())
                    ? headingPositions.get(hi + 1) - 1 : elements.size() - 1;
            Element headingElem = elements.get(start);
            int hLevel = headingElem.getHeadingLevel() != null ? headingElem.getHeadingLevel() : 0;
            String hText = headingElem.getContent() != null ? headingElem.getContent() : "";

            // 单节超过 200 元素 → 检查是否有子标题可以进一步细切
            if ((end - start + 1) > 200) {
                int subStart = start;
                for (int j = start + 1; j <= end; j++) {
                    Element e = elements.get(j);
                    // 找到级别更高的子标题且距上一分割点 > 50 元素时触发细切
                    if (e.getHeadingLevel() != null && e.getHeadingLevel() > hLevel
                            && e.getHeadingLevel() <= 3 && (j - subStart) > 50) {
                        result.add(new ChunkBoundary(subStart, j - 1, hLevel, hText));
                        subStart = j;
                        headingElem = e;
                        hLevel = e.getHeadingLevel() != null ? e.getHeadingLevel() : 0;
                        hText = e.getContent() != null ? e.getContent() : "";
                    }
                }
                // 最后一段
                result.add(new ChunkBoundary(subStart, end, hLevel, hText));
            } else {
                result.add(new ChunkBoundary(start, end, hLevel, hText));
            }
        }
        return result;
    }

    /** 查找 position 所属的 chunk 索引。线性扫描（chunks 数量通常不大）。 */
    private int findChunkIndex(List<ChunkBoundary> boundaries, int position) {
        for (int i = 0; i < boundaries.size(); i++) {
            if (position >= boundaries.get(i).start && position <= boundaries.get(i).end) return i;
        }
        return -1;
    }

    /**
     * 无标题时按 token 数做固定大小分块。
     * 每个 chunk 内累计元素直到逼近 MAX_TOKENS_PER_CHUNK。
     */
    static List<ChunkBoundary> computeTokenBasedChunks(List<Element> elements, int maxTokens) {
        List<ChunkBoundary> result = new ArrayList<>();
        if (elements.isEmpty()) return result;
        int chunkStart = 0;
        int currentTokens = 0;
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            int elemChars = (e.getContent() != null ? e.getContent().length() : 0)
                    + (e.getMetadata() != null ? e.getMetadata().length() : 0);
            int elemTokens = Math.max(1, elemChars / 3);  // 折中：~3 chars/token（兼顾中英文）
            if (currentTokens + elemTokens > maxTokens && i > chunkStart) {
                result.add(new ChunkBoundary(chunkStart, i - 1, 0, ""));
                chunkStart = i;
                currentTokens = elemTokens;
            } else {
                currentTokens += elemTokens;
            }
        }
        // 最后一块
        if (chunkStart < elements.size()) {
            result.add(new ChunkBoundary(chunkStart, elements.size() - 1, 0, ""));
        }
        return result;
    }

    /**
     * 估算指定范围内元素的 token 数。
     * 经验公式：中文 ~1.5 char/token，英文 ~4 char/token，取 ~2.5 char/token。
     */
    private int estimateTokens(List<Element> elements, int start, int end) {
        int chars = 0;
        for (int i = start; i <= end && i < elements.size(); i++) {
            Element e = elements.get(i);
            if (e.getContent() != null) chars += e.getContent().length();
            if (e.getMetadata() != null) chars += e.getMetadata().length();
        }
        return Math.max(1, (int) (chars / 2.5));
    }

    /** 标题感知分块的边界。 */
    static class ChunkBoundary {
        final int start, end, headingLevel;
        final String headingText;
        ChunkBoundary(int start, int end, int headingLevel, String headingText) {
            this.start = start; this.end = end;
            this.headingLevel = headingLevel; this.headingText = headingText;
        }
    }

    /**
     * 写出原始源文件副本（阶段 1 调用）。
     */
    public void writeSourceFile(Path docDir, byte[] rawBytes, String fileName) throws IOException {
        if (rawBytes.length > MAX_SOURCE_FILE_SIZE) {
            throw new IOException("Source file too large: " + fileName
                    + " (" + (rawBytes.length / (1024 * 1024)) + " MB), max is "
                    + (MAX_SOURCE_FILE_SIZE / (1024 * 1024)) + " MB");
        }
        Files.createDirectories(docDir);
        Files.write(docDir.resolve(fileName), rawBytes);
    }

    /**
     * 写出图片原始文件到 media/ 目录。
     * 跳过已存在的同名文件（避免重复写入）。
     */
    public void writeMedia(Path docDir, ExtractionResult result) throws IOException {
        if (result.getImages().isEmpty()) return;
        Path mediaDir = docDir.resolve("media");
        Files.createDirectories(mediaDir);
        for (ImageFile img : result.getImages()) {
            Path imgFile = mediaDir.resolve(img.getFileName());
            if (Files.exists(imgFile)) continue;
            Files.write(imgFile, img.getData());
        }
    }

    /**
     * 写出大表的完整 CSV + CSV 切片。
     * 逐行写出后立即释放 allRows 引用（调用 clearRows()），避免大表数据长期占用堆内存。
     */
    public void writeLargeTables(Path docDir, ExtractionResult result) throws IOException {
        if (result.getLargeTables().isEmpty()) return;
        Path dataDir = docDir.resolve("data");
        Files.createDirectories(dataDir);
        for (LargeTableInfo lt : result.getLargeTables()) {
            List<List<String>> rows = lt.getAllRows();
            if (rows.isEmpty()) continue;
            Path csvFile = dataDir.resolve(StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv");
            // 逐行写出 CSV
            try (BufferedWriter bw = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
                for (List<String> row : rows) {
                    bw.write(StringUtils.toCsvLine(row));
                    bw.newLine();
                }
            }
            // 解析列名列表，传递给 CsvSlicer（用于 index.json columns 字段）
            List<String> columns = Arrays.asList(lt.getSchema().split(" \\| "));
            CsvSlicer.sliceIfNeeded(dataDir, lt.getSheetName(), rows, columns);
            // 写出后立即清除对 allRows 的引用，允许 GC 回收
            lt.clearRows();
        }
    }

    /**
     * 构建 manifest.json 字符串。
     * 根节点包含 session_id、source_file 和 docs 列表。
     */
    public static String buildManifestJson(String sessionId, String sourceFile,
                                            List<DocInfo> docs) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("session_id", sessionId);
        root.put("source_file", sourceFile);
        List<Map<String, Object>> docList = new ArrayList<>();
        if (docs != null) {
            for (DocInfo d : docs) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("seq", d.seq);
                entry.put("dir", d.dir);
                entry.put("source", d.source);
                entry.put("source_copy", d.sourceCopy);
                entry.put("type", d.type);
                entry.put("element_count", d.elementCount);
                entry.put("data_ref_count", d.dataRefCount);
                entry.put("image_count", d.imageCount);
                entry.put("chunk_count", d.chunkCount);
                if (d.parentInfo != null) entry.put("parent", d.parentInfo);
                entry.put("status", d.status);
                if ("filtered".equals(d.status) && d.filterReason != null) {
                    entry.put("filter_reason", d.filterReason);
                    entry.put("filter_confidence", d.filterConfidence);
                }
                docList.add(entry);
            }
        }
        root.put("docs", docList);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to serialize manifest JSON", e);
            return "{\"error\": \"serialization failed\"}";
        }
    }

    /**
     * 文档元信息，用于 manifest.json 中的 docs 列表。
     */
    public static class DocInfo {
        /** 全局唯一序列号（会话内递增）。 */
        public final int seq;
        /** 文档所在目录名（已清洗过特殊字符）。 */
        public final String dir;
        /** 原始文件名。 */
        public final String source;
        /** 源文件副本路径。 */
        public final String sourceCopy;
        /** 文件类型标识。 */
        public final String type;
        /** 解析后的元素数量（Phase 2 回填）。 */
        public int elementCount;
        /** data_ref 数量。 */
        public int dataRefCount;
        /** 提取的图片数量。 */
        public int imageCount;
        /** 生成的 chunk 数量。 */
        public int chunkCount;
        /** 父级引用描述，如 "dirName, pos=N" 或 "dirName, element_pos=N"。 */
        public String parentInfo;
        /**
         * Phase 2 后用于 parent pos 修正的缓存：
         * key = EmbeddedFile.position（unpack 阶段的 position），
         * value = Element.position（Phase 2 解析后 embed 元素在 body.md 中的 POS 号）。
         */
        public Map<Integer, Integer> embedElementPositions;
        /** 状态："pending" / "done" / "filtered" / "error"。 */
        public String status;
        /** filtered 原因。 */
        public String filterReason;
        /** filtered 置信度。 */
        public double filterConfidence;

        /**
         * Phase 2 完成后的 DocInfo 构造函数（带统计计数）。
         */
        public DocInfo(int seq, String dir, String source, String sourceCopy, String type,
                       int elementCount, int dataRefCount, int imageCount, int chunkCount, String parentInfo) {
            this.seq = seq; this.dir = dir; this.source = source; this.sourceCopy = sourceCopy;
            this.type = type; this.elementCount = elementCount; this.dataRefCount = dataRefCount;
            this.imageCount = imageCount; this.chunkCount = chunkCount; this.parentInfo = parentInfo;
            this.status = "done";
        }

        /**
         * Phase 1 或过滤状态下的 DocInfo 构造函数（不带统计计数）。
         */
        public DocInfo(int seq, String dir, String source, String sourceCopy, String type,
                       String parentInfo, String status) {
            this.seq = seq; this.dir = dir; this.source = source; this.sourceCopy = sourceCopy;
            this.type = type; this.elementCount = 0; this.dataRefCount = 0; this.imageCount = 0;
            this.chunkCount = 0; this.parentInfo = parentInfo; this.status = status;
        }
    }

    /**
     * 写出单个 Element 到 body.md。
     * 格式：一行 "# POS: N | TYPE: xxx | metadata" + 内容 + 空行。
     */
    private void writeElement(BufferedWriter bw, Element elem) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("# POS: ").append(elem.getPosition());
        header.append(" | TYPE: ").append(elem.getType());
        if (elem.getMetadata() != null && !elem.getMetadata().isEmpty()) {
            // 将元信息中的连续空白替换为单个空格，保持格式整洁
            header.append(" | ").append(elem.getMetadata().replaceAll("\\s+", " "));
        }
        bw.write(header.toString()); bw.newLine();
        if (elem.getContent() != null && !elem.getContent().isEmpty()) {
            bw.write(elem.getContent()); bw.newLine();
        }
        bw.newLine();
    }
}
