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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

            // 按 position 合并写出 elements 和 data_refs
            List<Element> elements = result.getElements();
            List<LargeTableInfo> largeTables = result.getLargeTables();
            int eIdx = 0;
            int ltIdx = 0;
            while (eIdx < elements.size() || ltIdx < largeTables.size()) {
                int ePos = eIdx < elements.size() ? elements.get(eIdx).getPosition() : Integer.MAX_VALUE;
                int ltPos = ltIdx < largeTables.size() ? largeTables.get(ltIdx).getPosition() : Integer.MAX_VALUE;
                if (ePos <= ltPos) {
                    writeElement(bw, elements.get(eIdx++));
                } else {
                    writeBodyDataRef(bw, largeTables.get(ltIdx++));
                }
            }

            // 收集已被 Element TYPE: image 覆盖的 position，避免重复写图片引用
            Set<Integer> imageElemPositions = new HashSet<>();
            for (Element e : elements) {
                if ("image".equals(e.getType())) {
                    imageElemPositions.add(e.getPosition());
                }
            }
            for (ImageFile img : result.getImages()) {
                if (imageElemPositions.contains(img.getPosition())) continue;
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
                    writeLlmElement(bw, elements.get(j));
                }
            }
        }
    }

    /**
     * 标题感知分块（新 API）—— 基于 token 数而非元素数。
     * <p>分块策略：
     * <ol>
     *   <li>全文档内容 token ≤ MAX_TOKENS_WHOLE_DOC(3000) → 不分块</li>
     *   <li>有标题 → 按 H1→H2→H3 层级逐级切分，每块 ≤ MAX_TOKENS_PER_CHUNK(3000) token</li>
     *   <li>无标题 → 按 token 数回退到固定大小分块</li>
     * </ol>
     * <p>中文 token 估算：中文约 ~1.5 char/token，英文 ~4 char/token，取 ~2.5 char/token 作为折中。</p>
     */
    public int writeHierarchicalChunks(Path docDir, String docDirName, String mdFileName,
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
            return 0;
        }

        // 计算分块边界（先检测 sheet 边界，再按标题层级，最后回退到固定分块）
        List<ChunkBoundary> boundaries = computeSheetAwareChunks(elements);
        int totalChunks = boundaries.size();

        // 构建 index.json
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("source", docDirName + "/" + mdFileName);
        index.put("total_elements", totalElements);
        index.put("total_large_tables", totalLargeTables);
        index.put("total_images", totalImages);
        index.put("chunk_size", 0);
        boolean hasSheetHeaders = elements.stream().anyMatch(e -> "sheet_header".equals(e.getType()));
        index.put("chunk_mode", hasSheetHeaders ? "sheet"
                : totalChunks > 0 && boundaries.get(0).headingLevel > 0 ? "heading" : "token");
        index.put("approximate_tokens", totalTokens);

        // 预计算每个 chunk 的有效 position 范围（含 gap）：[firstElementPos, nextChunkFirstPos)
        List<LargeTableInfo> largeTables = result.getLargeTables();
        int[][] chunkPosRanges = new int[boundaries.size()][2];
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundary b = boundaries.get(i);
            int startPos = elements.get(b.start).getPosition();
            int endPosExcl = Integer.MAX_VALUE;
            if (i + 1 < boundaries.size()) {
                int nextIdx = boundaries.get(i + 1).start;
                if (nextIdx < elements.size()) {
                    endPosExcl = elements.get(nextIdx).getPosition();
                }
            }
            chunkPosRanges[i] = new int[]{startPos, endPosExcl};
        }

        List<Map<String, Object>> chunkList = new ArrayList<>();
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundary b = boundaries.get(i);
            String chunkFile = String.format("chunk_%04d.md", i + 1);
            int startPos = chunkPosRanges[i][0];
            int endPosExcl = chunkPosRanges[i][1];
            boolean hasLargeTable = largeTables.stream()
                    .anyMatch(lt -> lt.getPosition() >= startPos && lt.getPosition() < endPosExcl);
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
        List<Map<String, Object>> largeTableList = new ArrayList<>();
        for (LargeTableInfo lt : largeTables) {
            String csvFile = StringUtils.sanitizeFileName(lt.getSheetName()) + ".csv";
            int chunkIdx = findChunkIndex(elements, boundaries, chunkPosRanges, lt.getPosition());
            Map<String, Object> tbl = new LinkedHashMap<>();
            tbl.put("name", csvFile);
            tbl.put("sheet", lt.getSheetName());
            tbl.put("pos", lt.getPosition());
            tbl.put("rows", lt.getRowCount());
            tbl.put("file", "data/" + csvFile);
            if (chunkIdx >= 0) {
                tbl.put("in_chunk", String.format("chunk_%04d.md", chunkIdx + 1));
            }
            largeTableList.add(tbl);
        }
        index.put("large_tables", largeTableList);

        Files.writeString(chunksDir.resolve("index.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                StandardCharsets.UTF_8);

        // 逐 chunk 写出文件（含 gap 中的 data_ref）
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundary b = boundaries.get(i);
            int startPos = chunkPosRanges[i][0];
            int endPosExcl = chunkPosRanges[i][1];
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
                    writeLlmElement(bw, elements.get(j));
                }
                // 写出属于当前 chunk position 区间内的 data_ref
                for (LargeTableInfo lt : largeTables) {
                    if (lt.getPosition() >= startPos && lt.getPosition() < endPosExcl) {
                        writeLlmDataRef(bw, lt);
                    }
                }
            }
        }
        return totalChunks;
    }

    /** 不分块的 token 上限（约相当于 223 个中文元素的文档全文）。 */
    private static final int MAX_TOKENS_WHOLE_DOC = 3000;

    /** 单块最大 token 数（超过此值触发切分）。 */
    private static final int MAX_TOKENS_PER_CHUNK = 3000;

    /** 单块最小 token 数（低于此值触发合并）。 */
    private static final int MIN_TOKENS_PER_CHUNK = 800;

    /** 跨 chunk 重叠 token 数（用于 Excel sheet 内二次切分时的上下文保持）。 */
    private static final int OVERLAP_TOKENS = 600;

    /**
     * 计算标题感知的分块边界（基于 token 数，递归层级拆分）。
     * <p>算法：
     * <ol>
     *   <li>收集所有 H1-H3 标题位置</li>
     *   <li>无标题 → 返回空列表，上层回退到 {@link #computeTokenBasedChunks}</li>
     *   <li>按 H1 切分为大节，若大节 token 数 &gt; {@link #MAX_TOKENS_PER_CHUNK} → 递归按 H2 细分</li>
     *   <li>H2 节仍超阈值 → 递归按 H3 细分</li>
     *   <li>H3 仍超阈值 → 在该节内做固定 token 分块</li>
     * </ol>
     */
    static List<ChunkBoundary> computeHierarchicalChunks(List<Element> elements) {
        if (elements.isEmpty()) return new ArrayList<>();

        // 第一遍：收集所有 H1-H3 标题的位置
        List<Integer> headingPositions = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            if (e.getHeadingLevel() != null && e.getHeadingLevel() >= 1 && e.getHeadingLevel() <= 3) {
                headingPositions.add(i);
            }
        }
        // 没有任何标题 → 返回空列表，上层会回退到固定分块
        if (headingPositions.isEmpty()) return new ArrayList<>();

        List<ChunkBoundary> result = new ArrayList<>();
        splitByHeadingLevel(elements, 0, elements.size() - 1, 1, headingPositions, result);
        mergeSmallChunks(elements, result);
        return result;
    }

    /**
     * Sheet 感知分块（用于 Excel 等多 sheet 文档）。
     * <ol>
     *   <li>检测 {@code sheet_header} 类型元素作为 sheet 边界</li>
     *   <li>无 sheet 边界 → 回退到 {@link #computeHierarchicalChunks}</li>
     *   <li>按 sheet 分组，每 sheet 单独分块，不同 sheet 内容不在同一 chunk</li>
     *   <li>单 sheet 内容 > {@link #MAX_TOKENS_PER_CHUNK} 时，做带重叠窗口的二次切分</li>
     * </ol>
     */
    static List<ChunkBoundary> computeSheetAwareChunks(List<Element> elements) {
        // 检测 sheet 边界
        List<Integer> sheetStarts = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if ("sheet_header".equals(elements.get(i).getType())) {
                sheetStarts.add(i);
            }
        }

        if (sheetStarts.isEmpty()) {
            // 无 sheet → 用标题层级分块 + token 回退
            List<ChunkBoundary> result = computeHierarchicalChunks(elements);
            if (result.isEmpty()) {
                result = computeTokenBasedChunks(elements, MAX_TOKENS_PER_CHUNK);
                mergeSmallChunks(elements, result);
            }
            return result;
        }

        // 有 sheet → 按 sheet 分组
        List<ChunkBoundary> result = new ArrayList<>();
        for (int i = 0; i < sheetStarts.size(); i++) {
            int start = sheetStarts.get(i);
            int end = (i + 1 < sheetStarts.size()) ? sheetStarts.get(i + 1) - 1 : elements.size() - 1;

            int tokens = estimateTokens(elements, start, end);
            if (tokens <= MAX_TOKENS_PER_CHUNK) {
                result.add(new ChunkBoundary(start, end, 0, ""));
            } else {
                // 单 sheet 超阈值 → 带重叠窗口的二次切分
                result.addAll(splitSheetWithOverlap(elements, start, end));
            }
        }

        mergeSmallChunks(elements, result);
        return result;
    }

    /**
     * 将一个 sheet 内的元素按 token 数切分为多个 chunk，相邻 chunk 带重叠窗口。
     * 重叠量为 {@link #OVERLAP_TOKENS} token，用于保持 LLM 上下文连续性。
     */
    private static List<ChunkBoundary> splitSheetWithOverlap(List<Element> elements,
                                                              int sheetStart, int sheetEnd) {
        // 1. 先以 MAX_TOKENS_PER_CHUNK 做 token 分块（不含重叠）
        List<ChunkBoundary> base = computeTokenBasedChunks(
                elements.subList(sheetStart, sheetEnd + 1), MAX_TOKENS_PER_CHUNK);
        if (base.isEmpty()) return base;

        List<ChunkBoundary> result = new ArrayList<>();
        // 第一块保持不变
        ChunkBoundary first = base.get(0);
        result.add(new ChunkBoundary(first.start + sheetStart, first.end + sheetStart, 0, ""));

        // 后续块向前回滚至多 OVERLAP_TOKENS，制造重叠
        for (int i = 1; i < base.size(); i++) {
            ChunkBoundary curr = base.get(i);
            ChunkBoundary prev = base.get(i - 1);
            int currStart = curr.start + sheetStart;
            int currEnd = curr.end + sheetStart;

            // 从上一块末尾向前扫描，累加 token 直到达到 OVERLAP_TOKENS
            int overlapTokenAcc = 0;
            for (int j = prev.end + sheetStart; j >= currStart && overlapTokenAcc < OVERLAP_TOKENS; j--) {
                Element e = elements.get(j);
                int elemChars = (e.getContent() != null ? e.getContent().length() : 0)
                        + (e.getMetadata() != null ? e.getMetadata().length() : 0);
                overlapTokenAcc += Math.max(1, (int) (elemChars / 2.5));
                currStart = j;
            }

            result.add(new ChunkBoundary(currStart, currEnd, 0, ""));
        }
        return result;
    }

    /**
     * 递归按标题层级拆分，保证每块 token ≤ MAX_TOKENS_PER_CHUNK。
     *
     * @param start    当前处理范围起始索引（inclusive）
     * @param end      当前处理范围结束索引（inclusive）
     * @param level    当前要使用的标题层级（1=H1, 2=H2, 3=H3）
     * @param headings 所有 H1-H3 标题位置的排序列表
     * @param result   累积分块结果
     */
    private static void splitByHeadingLevel(List<Element> elements, int start, int end,
                                             int level, List<Integer> headings,
                                             List<ChunkBoundary> result) {
        if (start > end) return;
        int tokens = estimateTokens(elements, start, end);

        // 未超阈值 → 直接作为一块
        if (tokens <= MAX_TOKENS_PER_CHUNK || level > 3) {
            Element headingElem = elements.get(start);
            int hLevel = headingElem.getHeadingLevel() != null ? headingElem.getHeadingLevel() : 0;
            String hText = headingElem.getContent() != null ? headingElem.getContent() : "";
            result.add(new ChunkBoundary(start, end, hLevel, hText));
            return;
        }

        // 在当前范围内寻找 level 级别的标题
        List<Integer> headingsAtLevel = new ArrayList<>();
        for (int pos : headings) {
            if (pos < start) continue;
            if (pos > end) break;
            Element e = elements.get(pos);
            if (e.getHeadingLevel() != null && e.getHeadingLevel() == level) {
                headingsAtLevel.add(pos);
            }
        }

        if (headingsAtLevel.isEmpty()) {
            // 当前层级没有标题 → 降级到固定 token 分块
            // 注意：computeTokenBasedChunks 返回的索引是相对 subList 的，需要 offset +start
            int offset = start;
            for (ChunkBoundary cb : computeTokenBasedChunks(
                    elements.subList(start, end + 1), MAX_TOKENS_PER_CHUNK)) {
                result.add(new ChunkBoundary(cb.start + offset, cb.end + offset,
                        cb.headingLevel, cb.headingText));
            }
            return;
        }

        // 按标题切分，每个子块若超阈值则递归下钻
        for (int i = 0; i < headingsAtLevel.size(); i++) {
            int chunkStart = headingsAtLevel.get(i);
            int chunkEnd = (i + 1 < headingsAtLevel.size())
                    ? headingsAtLevel.get(i + 1) - 1 : end;
            splitByHeadingLevel(elements, chunkStart, chunkEnd, level + 1, headings, result);
        }
    }

    /**
     * 后处理：合并 token 数过小的相邻 chunk。
     * 碎片 chunk 浪费文件句柄且对 LLM 无意义，与相邻 chunk 合并效果更好。
     * 从右向左扫描，优先与上一个 chunk 合并；首 chunk 过小时与下一个合并。
     * 合并后 chunk 的预估 token 数不得超过 {@link #MAX_TOKENS_PER_CHUNK}，否则终止该次合并。
     */
    static void mergeSmallChunks(List<Element> elements, List<ChunkBoundary> boundaries) {
        if (boundaries.size() < 2) return;
        // 首 chunk 过小 → 尝试与下一个合并（仅同 sheet 可合并）
        ChunkBoundary first = boundaries.get(0);
        if (estimateTokens(elements, first.start, first.end) < MIN_TOKENS_PER_CHUNK) {
            ChunkBoundary second = boundaries.get(1);
            int mergedTokens = estimateTokens(elements, first.start, second.end);
            if (mergedTokens <= MAX_TOKENS_PER_CHUNK && isSameSheet(elements, first, second)) {
                boundaries.set(0, new ChunkBoundary(first.start, second.end, first.headingLevel, first.headingText));
                boundaries.remove(1);
            }
        }
        // 从右向左合并其余过小的 chunk（不会触及 index 0，因首块已处理）
        for (int i = boundaries.size() - 1; i >= 1; i--) {
            ChunkBoundary cb = boundaries.get(i);
            int tokens = estimateTokens(elements, cb.start, cb.end);
            if (tokens >= MIN_TOKENS_PER_CHUNK) continue;
            ChunkBoundary prev = boundaries.get(i - 1);
            if (!isSameSheet(elements, prev, cb)) continue;
            int mergedTokens = estimateTokens(elements, prev.start, cb.end);
            if (mergedTokens > MAX_TOKENS_PER_CHUNK) continue;
            boundaries.set(i - 1, new ChunkBoundary(prev.start, cb.end, prev.headingLevel, prev.headingText));
            boundaries.remove(i);
        }
    }

    /** 检查两个 chunk 边界是否属于同一个 sheet（区间内不存在 sheet_header 分隔）。 */
    private static boolean isSameSheet(List<Element> elements, ChunkBoundary a, ChunkBoundary b) {
        int start = Math.min(a.start, b.start);
        int end = Math.max(a.end, b.end);
        for (int i = start + 1; i <= end; i++) {
            if (i >= 0 && i < elements.size() && "sheet_header".equals(elements.get(i).getType())) {
                return false;
            }
        }
        return true;
    }

    /** 查找 position 所属的 chunk 索引（用预计算的 position 范围匹配，含 gap）。 */
    private static int findChunkIndex(List<Element> elements, List<ChunkBoundary> boundaries,
                                       int[][] chunkPosRanges, int position) {
        for (int i = 0; i < boundaries.size(); i++) {
            if (position >= chunkPosRanges[i][0] && position < chunkPosRanges[i][1]) return i;
        }
        // fallback: 直接匹配元素 index 范围（兼容无 data_ref 场景）
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
            int elemTokens = Math.max(1, (int) (elemChars / 2.5));
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
    static int estimateTokens(List<Element> elements, int start, int end) {
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
            // 逐行写出 CSV（含 UTF-8 BOM，确保 Excel 正确识别中文编码）
            try (BufferedWriter bw = StringUtils.newBomWriter(csvFile)) {
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
        root.put("_comment", "会话级提取清单。—— session_id: 提取会话唯一标识; source_file: 源文件名; "
                + "docs: 文档元信息列表。每个文档字段说明：seq=序号, dir=输出目录, "
                + "type=文档类型(docx/xlsx/pdf等), element_count=body.md元素总数, "
                + "data_ref_count=大表(CSV)数量, image_count=图片数, chunk_count=切片数, "
                + "parent=父文档引用(格式\"目录名, element_pos=N\", N表示在父文档body.md中的元素位置), "
                + "status=处理状态(done/filtered/error)");
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
                entry.put("element_count", d.getElementCount());
                entry.put("data_ref_count", d.getDataRefCount());
                entry.put("image_count", d.getImageCount());
                entry.put("chunk_count", d.getChunkCount());
                if (d.getParentInfo() != null) entry.put("parent", d.getParentInfo());
                entry.put("status", d.getStatus());
                if ("filtered".equals(d.getStatus()) && d.getFilterReason() != null) {
                    entry.put("filter_reason", d.getFilterReason());
                    entry.put("filter_confidence", d.getFilterConfidence());
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
     * 构建文档层级树 JSON，供 AI 层直接消费。
     * <p>将 manifest.json 中的 docs 扁平列表重建为树状结构：
     * <ul>
     *   <li>无 parent 的文档为根节点（roots 列表）</li>
     *   <li>有 parent 的文档按 dir 匹配挂载到对应父节点的 children 中</li>
     *   <li>每个节点包含 seq / name / type / status / element_count 及 element_pos_in_parent</li>
     * </ul>
     *
     * @param docs DocInfo 列表
     * @return 美化 JSON 字符串
     */
    public static String buildTreeJson(List<DocInfo> docs) {
        // 第一遍：按 dir 建立索引
        Map<String, DocInfo> dirIndex = new LinkedHashMap<>();
        // 根节点列表
        List<DocInfo> roots = new ArrayList<>();
        // parentDir → children 列表
        Map<String, List<DocInfo>> parentMap = new LinkedHashMap<>();
        for (DocInfo d : docs) {
            dirIndex.put(d.dir, d);
            // 解析 parentInfo："dirName, element_pos=N" 或 "dirName, pos=N"
            String parentDir = null;
            if (d.getParentInfo() != null) {
                int comma = d.getParentInfo().indexOf(", ");
                if (comma > 0) {
                    parentDir = d.parentInfo.substring(0, comma);
                }
            }
            if (parentDir == null) {
                roots.add(d);
            } else {
                parentMap.computeIfAbsent(parentDir, k -> new ArrayList<>()).add(d);
            }
        }

        // 递归构建树节点
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_comment", "文档层级树，供AI直接消费。—— roots: 根文档列表。每个节点字段："
                + "seq=序号, name=目录名, type=文档类型(docx/xlsx/pdf等), status=处理状态, "
                + "element_count=元素总数, element_pos_in_parent=在父文档body.md中的元素POS号, "
                + "children=子文档列表");
        List<Map<String, Object>> rootNodes = new ArrayList<>();
        for (DocInfo r : roots) {
            rootNodes.add(buildTreeNode(r, parentMap));
        }
        root.put("roots", rootNodes);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to serialize tree JSON", e);
            return "{\"error\": \"serialization failed\"}";
        }
    }

    private static Map<String, Object> buildTreeNode(DocInfo d, Map<String, List<DocInfo>> parentMap) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("seq", d.seq);
        node.put("name", d.dir);
        node.put("type", d.type);
        node.put("status", d.getStatus());
        node.put("element_count", d.getElementCount());
        // 从 parentInfo 中解析 element_pos
        String pi = d.getParentInfo();
        if (pi != null) {
            if (pi.contains("element_pos=")) {
                String[] parts = pi.split("element_pos=", 2);
                if (parts.length == 2) {
                    try {
                        node.put("element_pos_in_parent", Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse element_pos in parentInfo: {}", pi);
                    }
                }
            } else if (pi.contains("pos=")) {
                String[] parts = pi.split("pos=", 2);
                if (parts.length == 2) {
                    try {
                        node.put("element_pos_in_parent", Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse pos in parentInfo: {}", pi);
                    }
                }
            }
        }
        // 子节点
        List<Map<String, Object>> children = new ArrayList<>();
        List<DocInfo> childDocs = parentMap.get(d.dir);
        if (childDocs != null) {
            for (DocInfo child : childDocs) {
                children.add(buildTreeNode(child, parentMap));
            }
        }
        if (!children.isEmpty()) {
            node.put("children", children);
        }
        return node;
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
        private int elementCount;
        /** data_ref 数量。 */
        private int dataRefCount;
        /** 提取的图片数量。 */
        private int imageCount;
        /** 生成的 chunk 数量。 */
        private int chunkCount;
        /** 父级引用描述，如 "dirName, pos=N" 或 "dirName, element_pos=N"。 */
        private String parentInfo;
        /**
         * Phase 2 后用于 parent pos 修正的缓存：
         * key = EmbeddedFile.position（unpack 阶段的 position），
         * value = Element.position（Phase 2 解析后 embed 元素在 body.md 中的 POS 号）。
         */
        private Map<Integer, Integer> embedElementPositions;
        /** 状态："pending" / "done" / "filtered" / "error"。 */
        private String status;
        /** filtered 原因。 */
        private String filterReason;
        /** filtered 置信度。 */
        private double filterConfidence;

        public int getElementCount() { return elementCount; }
        public void setElementCount(int elementCount) { this.elementCount = elementCount; }
        public int getDataRefCount() { return dataRefCount; }
        public void setDataRefCount(int dataRefCount) { this.dataRefCount = dataRefCount; }
        public int getImageCount() { return imageCount; }
        public void setImageCount(int imageCount) { this.imageCount = imageCount; }
        public int getChunkCount() { return chunkCount; }
        public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
        public String getParentInfo() { return parentInfo; }
        public void setParentInfo(String parentInfo) { this.parentInfo = parentInfo; }
        public Map<Integer, Integer> getEmbedElementPositions() { return embedElementPositions; }
        public void setEmbedElementPositions(Map<Integer, Integer> embedElementPositions) { this.embedElementPositions = embedElementPositions; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getFilterReason() { return filterReason; }
        public void setFilterReason(String filterReason) { this.filterReason = filterReason; }
        public double getFilterConfidence() { return filterConfidence; }
        public void setFilterConfidence(double filterConfidence) { this.filterConfidence = filterConfidence; }

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

    /**
     * 写出 LargeTableInfo（data_ref）到 body.md。
     * 格式：一行 "# POS: N | TYPE: data_ref | schema: ... | rows: N | file: data/xxx.csv"
     *       后续行以 "> " 前缀输出 preview 文本。
     */
    private void writeBodyDataRef(BufferedWriter bw, LargeTableInfo lt) throws IOException {
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

    /**
     * LLM 友好的 compact 格式写出 data_ref（用于 chunks）。
     * 格式：[data: schema | Rows: N]
     */
    private static void writeLlmDataRef(BufferedWriter bw, LargeTableInfo lt) throws IOException {
        bw.write("[data: " + lt.getSchema() + " | Rows: " + lt.getRowCount() + "]");
        bw.newLine();
        bw.newLine();
    }

    /**
     * LLM 友好的 compact 格式写出 Element（用于 chunks）。
     * <ul>
     *   <li>标题（headingLevel &gt; 0）→ Markdown 标题语法 ## text</li>
     *   <li>段落/表格/页眉/页脚 → 直接输出 Markdown 原文（LLM 原生理解）</li>
     *   <li>图片 → [image: filename]</li>
     *   <li>嵌入文件 → [embed: filename]</li>
     *   <li>大表引用 → [data: metadata]</li>
     * </ul>
     */
    private void writeLlmElement(BufferedWriter bw, Element elem) throws IOException {
        String type = elem.getType();
        String content = elem.getContent();
        String metadata = elem.getMetadata();
        Integer headingLevel = elem.getHeadingLevel();

        // paragraph / table / header / footer → 直接输出 Markdown 原文
        if ("paragraph".equals(type) || "table".equals(type)
                || "header".equals(type) || "footer".equals(type)) {
            if (headingLevel != null && headingLevel > 0 && content != null && !content.isEmpty()) {
                // 标题用 Markdown 标题语法
                int level = Math.min(headingLevel, 6);
                bw.write("#".repeat(level) + " " + content);
            } else if (content != null && !content.isEmpty()) {
                bw.write(content);
            }
            bw.newLine(); bw.newLine();
            return;
        }

        // image → [image: filename]
        if ("image".equals(type)) {
            bw.write(formatCompactRef("image", metadata));
            bw.newLine(); bw.newLine();
            return;
        }

        // embed → [embed: filename]
        if ("embed".equals(type)) {
            bw.write(formatCompactRef("embed", metadata));
            bw.newLine(); bw.newLine();
            return;
        }

        // data_ref → [data: Columns: ... | Rows: N]
        if ("data_ref".equals(type)) {
            bw.write("[data: " + (metadata != null ? metadata.replaceAll("\\s+", " ") : "") + "]");
            bw.newLine(); bw.newLine();
        }
    }

    /**
     * 从 metadata 中提取文件名并格式化为 [type: filename]。
     * metadata 格式为 "file: path/to/filename.ext" 或直接为文件名。
     */
    private static String formatCompactRef(String type, String metadata) {
        if (metadata == null || metadata.isEmpty()) return "[" + type + "]";
        String meta = metadata.replaceAll("\\s+", " ").trim();
        String name = meta.startsWith("file:") ? meta.substring(5).trim() : meta;
        return "[" + type + ": " + name + "]";
    }
}
