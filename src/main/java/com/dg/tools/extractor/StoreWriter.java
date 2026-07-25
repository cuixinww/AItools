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
 * 结构化产物写出器（StoreWriter）。
 *
 * 负责把解析结果 {@link ExtractionResult} 落盘为以下产物：
 *   - body.md        ：正文 Markdown（含 # POS / TYPE 头、大表 data_ref、图片引用）
 *   - chunks/        ：按固定元素数切片的小文件 + index.json（供 LLM 按需加载）
 *   - data/          ：大表的完整 CSV + 必要时切片（>500 行时）
 *   - media/         ：图片原始文件
 *   - <源文件>       ：原始二进制副本（在 RecursiveExtractor 阶段 1 写出）
 *   - manifest.json  ：会话级清单（由 buildManifestJson 生成）
 *
 * 该工具类无状态（除静态常量与共享 ObjectMapper 外），可安全复用。
 */
@Slf4j
public class StoreWriter {

    /** 单个 chunk 包含的元素数量（默认 50）。 */
    private static final int CHUNK_SIZE = 50;

    /** Jackson 的 JSON 序列化器（线程安全，可复用）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 源文件副本写入磁盘的大小上限（200 MB）。 */
    private static final int MAX_SOURCE_FILE_SIZE = 200 * 1024 * 1024;

    /**
     * 写出正文 body.md。
     * 文件头依次写入 DOC / SOURCE / PARENT 三段元信息（非根文档才带 PARENT），
     * 随后按元素顺序写出每个 Element，再写出大表 data_ref 与图片引用。
     *
     * @param docDir     文档输出目录
     * @param mdFileName 正文 Markdown 文件名
     * @param result     解析结果
     * @param seq        文档序号
     * @param sourceFile 源文件名
     * @param parentInfo 父级文档信息（可为 null）
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

            // 正文元素（段落 / 表格 / 嵌入等）
            for (Element elem : result.getElements()) {
                writeElement(bw, elem);
            }

            // 大表以 data_ref 形式引用（含 schema / 行数 / 预览 / 指向 data/*.csv）
            for (LargeTableInfo lt : result.getLargeTables()) {
                bw.write("# POS: " + lt.getPosition() + " | TYPE: data_ref | " +
                        "schema: " + lt.getSchema() + " | rows: " + lt.getRowCount() +
                         " | file: data/" + StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv");
                bw.newLine();
                if (lt.getPreview() != null && !lt.getPreview().isEmpty()) {
                    // 预览行前加 "> " 前缀，避免破坏 Markdown 结构
                    for (String line : lt.getPreview().split("\\r?\\n")) {
                        bw.write("> " + line);
                        bw.newLine();
                    }
                }
                bw.newLine();
            }

            // 图片引用（指向 media/ 下原始文件）
            for (ImageFile img : result.getImages()) {
                bw.write("# POS: " + img.getPosition() + " | TYPE: image | " +
                        "file: media/" + StringUtils.sanitizeFileName(img.getFileName()));
                bw.newLine();
                bw.newLine();
            }
        }
    }

    /**
     * 写出 chunks/ 目录：index.json + 若干 chunk_NNNN.md。
     * 切片按 CHUNK_SIZE 个元素一文件，index.json 记录总元素数、大表位置、
     * 以及每个 chunk 的 pos 区间与是否包含大表，便于 AI 层按需定位加载。
     */
    public void writeChunks(Path docDir, String docDirName, String mdFileName, ExtractionResult result) throws IOException {
        int totalElements = result.getElements().size();
        int totalLargeTables = result.getLargeTables().size();
        int totalImages = result.getImages().size();

        Path chunksDir = docDir.resolve("chunks");
        Files.createDirectories(chunksDir);

        int totalChunks = (totalElements + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (totalChunks == 0) totalChunks = 1;

        // 用 Jackson 写出 index.json
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

            // 判断本 chunk 区间内是否包含大表位置
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

        // 大表索引：记录每个大表对应的 CSV 文件及所在 chunk
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

        // 写出每个 chunk 文件（含头部与区间内元素）
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
     * 写出原始源文件副本（阶段 1 调用），用于审计与重试。
     *
     * @throws IOException 当文件超过大小上限或写出失败时
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
     * 写出图片原始文件。阶段 1 已写过则跳过（避免覆盖，也避免重复 IO）。
     */
    public void writeMedia(Path docDir, ExtractionResult result) throws IOException {
        if (result.getImages().isEmpty()) return;
        Path mediaDir = docDir.resolve("media");
        Files.createDirectories(mediaDir);

        for (ImageFile img : result.getImages()) {
            Path imgFile = mediaDir.resolve(img.getFileName());
            // 阶段 1 已经写过这张图片 —— 不再覆盖
            if (Files.exists(imgFile)) continue;
            Files.write(imgFile, img.getData());
        }
    }

    /**
     * 写出大表的完整 CSV（data/{sheet}.csv），并在超过切片阈值时交由 CsvSlicer 切片。
     * schema 字符串以 " | " 分隔，用作 CSV 的列名列表。
     */
    public void writeLargeTables(Path docDir, ExtractionResult result) throws IOException {
        if (result.getLargeTables().isEmpty()) return;
        Path dataDir = docDir.resolve("data");
        Files.createDirectories(dataDir);

        for (LargeTableInfo lt : result.getLargeTables()) {
            Path csvFile = dataDir.resolve(StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv");
            try (BufferedWriter bw = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
                for (List<String> row : lt.getAllRows()) {
                    bw.write(StringUtils.toCsvLine(row));
                    bw.newLine();
                }
            }
            // 行数超过阈值则切分为若干小 CSV 文件
            List<String> columns = Arrays.asList(lt.getSchema().split(" \\| "));
            CsvSlicer.sliceIfNeeded(dataDir, lt.getSheetName(), lt.getAllRows(), columns);
        }
    }

    /**
     * 构建并序列化 manifest.json 文本。
     * 包含 session_id、根 source_file，以及每个文档的 seq / dir / source / type /
     * 统计计数 / parent / status（filtered 时附带原因与置信度）。
     */
    public static String buildManifestJson(String sessionId, String sourceFile,
                                            List<DocInfo> docs) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("session_id", sessionId);
        root.put("source_file", sourceFile);

        List<Map<String, Object>> docList = new ArrayList<>();
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
            if (d.parentInfo != null) {
                entry.put("parent", d.parentInfo);
            }
            entry.put("status", d.status);
            if ("filtered".equals(d.status) && d.filterReason != null) {
                entry.put("filter_reason", d.filterReason);
                entry.put("filter_confidence", d.filterConfidence);
            }
            docList.add(entry);
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
     * 文档元信息（写 manifest 用）。
     * 提供两个构造器：一个用于阶段 1（仅基础字段 + status），一个用于阶段 2 回填统计。
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

        // 阶段 2 回填统计版构造器
        public DocInfo(int seq, String dir, String source, String sourceCopy, String type,
                       int elementCount, int dataRefCount, int imageCount, int chunkCount, String parentInfo) {
            this.seq = seq;
            this.dir = dir;
            this.source = source;
            this.sourceCopy = sourceCopy;
            this.type = type;
            this.elementCount = elementCount;
            this.dataRefCount = dataRefCount;
            this.imageCount = imageCount;
            this.chunkCount = chunkCount;
            this.parentInfo = parentInfo;
            this.status = "done";
        }

        // 阶段 1 基础版构造器（仅记录基础字段与初始状态）
        public DocInfo(int seq, String dir, String source, String sourceCopy, String type,
                       String parentInfo, String status) {
            this.seq = seq;
            this.dir = dir;
            this.source = source;
            this.sourceCopy = sourceCopy;
            this.type = type;
            this.elementCount = 0;
            this.dataRefCount = 0;
            this.imageCount = 0;
            this.chunkCount = 0;
            this.parentInfo = parentInfo;
            this.status = status;
        }
    }

    /**
     * 写出一个 Element 到 body.md / chunk 文件。
     * 格式：以 "# POS: N | TYPE: xxx | metadata" 作为单行头，随后写内容（空行分隔）。
     * metadata 中的换行会被压缩为空格，保证头部始终单行。
     */
    private void writeElement(BufferedWriter bw, Element elem) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("# POS: ").append(elem.getPosition());
        header.append(" | TYPE: ").append(elem.getType());
        if (elem.getMetadata() != null && !elem.getMetadata().isEmpty()) {
            // 替换换行，保持头部单行
            header.append(" | ").append(elem.getMetadata().replaceAll("\\s+", " "));
        }
        bw.write(header.toString()); bw.newLine();
        if (elem.getContent() != null && !elem.getContent().isEmpty()) {
            bw.write(elem.getContent()); bw.newLine();
        }
        bw.newLine();
    }

}
