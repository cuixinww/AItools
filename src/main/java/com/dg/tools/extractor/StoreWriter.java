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
import java.util.*;

/**
 * 结构化产物写出器（StoreWriter）。
 *
 * 负责把解析结果 {@link ExtractionResult} 落盘为以下产物：
 *   - body.md        ：正文 Markdown（含 # POS / TYPE 头、大表 data_ref、图片引用）
 *   - chunks/        ：标题感知分块 + index.json（供 LLM 按需加载）
 *   - data/          ：大表的完整 CSV + 必要时切片（>500 行时）
 *   - media/         ：图片原始文件
 *   - 源文件         ：原始二进制副本（在 RecursiveExtractor 阶段 1 写出）
 *   - manifest.json  ：会话级清单（由 buildManifestJson 生成）
 *
 * 该工具类无状态（除静态常量与共享 ObjectMapper 外），可安全复用。
 */
@Slf4j
public class StoreWriter {

    /** 单个 chunk 包含的元素数量（默认 50，仅用于回退分块）。 */
    private static final int CHUNK_SIZE = 50;

    /** Jackson 的 JSON 序列化器（线程安全，可复用）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 源文件副本写入磁盘的大小上限（200 MB）。 */
    private static final int MAX_SOURCE_FILE_SIZE = 200 * 1024 * 1024;

    /**
     * 写出正文 body.md。
     */
    public void writeBody(Path docDir, String mdFileName, ExtractionResult result,
                          int seq, String sourceFile, String parentInfo) throws IOException {
        Files.createDirectories(docDir);
        Path bodyFile = docDir.resolve(mdFileName);

        try (BufferedWriter bw = Files.newBufferedWriter(bodyFile, StandardCharsets.UTF_8)) {
            bw.write("# DOC: " + seq); bw.newLine();
            bw.write("# SOURCE: " + sourceFile); bw.newLine();
            if (parentInfo != null) {
                bw.write("# PARENT: " + parentInfo); bw.newLine();
            }
            bw.newLine();

            for (Element elem : result.getElements()) {
                writeElement(bw, elem);
            }

            for (LargeTableInfo lt : result.getLargeTables()) {
                bw.write("# POS: " + lt.getPosition() + " | TYPE: data_ref | " +
                        "schema: " + lt.getSchema() + " | rows: " + lt.getRowCount() +
                         " | file: data/" + StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv");
                bw.newLine();
                if (lt.getPreview() != null && !lt.getPreview().isEmpty()) {
                    for (String line : lt.getPreview().split("\\r?\\n")) {
                        bw.write("> " + line);
                        bw.newLine();
                    }
                }
                bw.newLine();
            }

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
     * 标题感知分块（新 API）。
     *
     * 分块策略：
     *   1) 总元素数 ≤ 阈值(300) → 不分块；
     *   2) 有标题 → 先按 H1 切，子节过长再按 H2/H3 细切；
     *   3) 无标题 → 回退到固定 CHUNK_SIZE 机械分块。
     */
    public void writeHierarchicalChunks(Path docDir, String docDirName, String mdFileName,
                                         ExtractionResult result) throws IOException {
        int totalElements = result.getElements().size();
        int totalLargeTables = result.getLargeTables().size();
        int totalImages = result.getImages().size();

        Path chunksDir = docDir.resolve("chunks");
        Files.createDirectories(chunksDir);

        List<Element> elements = result.getElements();

        if (totalElements <= 300) {
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("source", docDirName + "/" + mdFileName);
            index.put("total_elements", totalElements);
            index.put("total_large_tables", totalLargeTables);
            index.put("total_images", totalImages);
            index.put("chunk_size", 0);
            index.put("chunks", List.of());
            Files.writeString(chunksDir.resolve("index.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                    StandardCharsets.UTF_8);
            return;
        }

        List<ChunkBoundary> boundaries = computeHierarchicalChunks(elements);
        int totalChunks = boundaries.size();
        if (totalChunks == 0) {
            totalChunks = (totalElements + CHUNK_SIZE - 1) / CHUNK_SIZE;
            boundaries = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(start + CHUNK_SIZE, totalElements) - 1;
                boundaries.add(new ChunkBoundary(start, end, 0, ""));
            }
        }

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("source", docDirName + "/" + mdFileName);
        index.put("total_elements", totalElements);
        index.put("total_large_tables", totalLargeTables);
        index.put("total_images", totalImages);
        index.put("chunk_size", 0);

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
            if (b.headingLevel > 0) {
                chunk.put("heading_level", b.headingLevel);
                chunk.put("heading_text", b.headingText);
            }
            chunk.put("approximate_tokens", estimateTokens(elements, b.start, b.end));
            chunk.put("includes_large_table", hasLargeTable);
            chunkList.add(chunk);
        }
        index.put("chunks", chunkList);

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

    static List<ChunkBoundary> computeHierarchicalChunks(List<Element> elements) {
        List<ChunkBoundary> result = new ArrayList<>();
        if (elements.isEmpty()) return result;

        List<Integer> headingPositions = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            if (e.getHeadingLevel() != null && e.getHeadingLevel() >= 1 && e.getHeadingLevel() <= 3) {
                headingPositions.add(i);
            }
        }
        if (headingPositions.isEmpty()) return result;

        for (int hi = 0; hi < headingPositions.size(); hi++) {
            int start = headingPositions.get(hi);
            int end = (hi + 1 < headingPositions.size())
                    ? headingPositions.get(hi + 1) - 1 : elements.size() - 1;
            Element headingElem = elements.get(start);
            int hLevel = headingElem.getHeadingLevel() != null ? headingElem.getHeadingLevel() : 0;
            String hText = headingElem.getContent() != null ? headingElem.getContent() : "";
            if ((end - start + 1) > 200) {
                int subStart = start;
                for (int j = start + 1; j <= end; j++) {
                    Element e = elements.get(j);
                    if (e.getHeadingLevel() != null && e.getHeadingLevel() > hLevel
                            && e.getHeadingLevel() <= 3 && (j - subStart) > 50) {
                        result.add(new ChunkBoundary(subStart, j - 1, hLevel, hText));
                        subStart = j;
                        headingElem = e;
                        hLevel = e.getHeadingLevel() != null ? e.getHeadingLevel() : 0;
                        hText = e.getContent() != null ? e.getContent() : "";
                    }
                }
                result.add(new ChunkBoundary(subStart, end, hLevel, hText));
            } else {
                result.add(new ChunkBoundary(start, end, hLevel, hText));
            }
        }
        return result;
    }

    private int findChunkIndex(List<ChunkBoundary> boundaries, int position) {
        for (int i = 0; i < boundaries.size(); i++) {
            if (position >= boundaries.get(i).start && position <= boundaries.get(i).end) return i;
        }
        return -1;
    }

    private int estimateTokens(List<Element> elements, int start, int end) {
        int chars = 0;
        for (int i = start; i <= end && i < elements.size(); i++) {
            Element e = elements.get(i);
            if (e.getContent() != null) chars += e.getContent().length();
            if (e.getMetadata() != null) chars += e.getMetadata().length();
        }
        return Math.max(1, chars / 4);
    }

    /** 分块边界。 */
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
     * 写出图片原始文件。
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
     * 逐行写出后立即释放 allRows 引用，避免大表数据长期占用堆内存。
     */
    public void writeLargeTables(Path docDir, ExtractionResult result) throws IOException {
        if (result.getLargeTables().isEmpty()) return;
        Path dataDir = docDir.resolve("data");
        Files.createDirectories(dataDir);
        for (LargeTableInfo lt : result.getLargeTables()) {
            List<List<String>> rows = lt.getAllRows();
            if (rows.isEmpty()) continue;
            Path csvFile = dataDir.resolve(StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv");
            try (BufferedWriter bw = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
                for (List<String> row : rows) {
                    bw.write(StringUtils.toCsvLine(row));
                    bw.newLine();
                }
            }
            List<String> columns = Arrays.asList(lt.getSchema().split(" \\| "));
            CsvSlicer.sliceIfNeeded(dataDir, lt.getSheetName(), rows, columns);
            // 写出后立即清除对 allRows 的引用，允许 GC 回收
            lt.clearRows();
        }
    }

    /**
     * 构建 manifest.json。
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
     * 文档元信息。
     */
    public static class DocInfo {
        public final int seq;
        public final String dir;
        public final String source;
        public final String sourceCopy;
        public final String type;
        public int elementCount;
        public int dataRefCount;
        public int imageCount;
        public int chunkCount;
        public final String parentInfo;
        public String status;
        public String filterReason;
        public double filterConfidence;

        public DocInfo(int seq, String dir, String source, String sourceCopy, String type,
                       int elementCount, int dataRefCount, int imageCount, int chunkCount, String parentInfo) {
            this.seq = seq; this.dir = dir; this.source = source; this.sourceCopy = sourceCopy;
            this.type = type; this.elementCount = elementCount; this.dataRefCount = dataRefCount;
            this.imageCount = imageCount; this.chunkCount = chunkCount; this.parentInfo = parentInfo;
            this.status = "done";
        }

        public DocInfo(int seq, String dir, String source, String sourceCopy, String type,
                       String parentInfo, String status) {
            this.seq = seq; this.dir = dir; this.source = source; this.sourceCopy = sourceCopy;
            this.type = type; this.elementCount = 0; this.dataRefCount = 0; this.imageCount = 0;
            this.chunkCount = 0; this.parentInfo = parentInfo; this.status = status;
        }
    }

    private void writeElement(BufferedWriter bw, Element elem) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("# POS: ").append(elem.getPosition());
        header.append(" | TYPE: ").append(elem.getType());
        if (elem.getMetadata() != null && !elem.getMetadata().isEmpty()) {
            header.append(" | ").append(elem.getMetadata().replaceAll("\\s+", " "));
        }
        bw.write(header.toString()); bw.newLine();
        if (elem.getContent() != null && !elem.getContent().isEmpty()) {
            bw.write(elem.getContent()); bw.newLine();
        }
        bw.newLine();
    }
}
