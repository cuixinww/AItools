package com.dg.tools.extractor.excel;

import com.dg.tools.extractor.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CSV 切片器（CsvSlicer）。
 *
 * 当大表的行数超过阈值（默认 500 行）时，把完整的 CSV 进一步切分为多个小文件，
 * 每个切片 100 行，并生成一个 index.json 描述列名、总行数与各切片行区间，
 * 便于 AI 层按需加载、避免一次性读入过长文本。
 *
 * 行号在 index.json 中使用 1-based（对人类友好），
 * 而 allRows 的索引是 0-based（Java 标准）。
 *
 * 说明：切片目录命名为 data/{sheet_name}/，每次写入前会清理旧切片，避免残留。
 */
@Slf4j
public class CsvSlicer {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 触发切片的总行数阈值（超过则切片）。 */
    public static final int SLICE_THRESHOLD = 500;

    /** 每个 CSV 切片的行数。 */
    public static final int CHUNK_SIZE = 100;

    /** 工具类，禁止外部实例化。 */
    private CsvSlicer() {}

    /**
     * 按需把 CSV 切片并写出 index.json 到切片目录。
     * 若行数未超过阈值，则不做任何切片（仅由上层写出完整 CSV）。
     *
     * @param dataDir   完整 CSV 所在目录（data/）
     * @param sheetName 工作表名（用于切片目录命名）
     * @param allRows   全部数据行（已从表头行开始）
     * @param columns   列名列表（用于 index.json），可为 null（跳过切片）
     * @throws IOException 切片过程中出现 I/O 错误时抛出
     */
    public static void sliceIfNeeded(Path dataDir, String sheetName,
                                       List<List<String>> allRows, List<String> columns) throws IOException {
        if (allRows.size() <= SLICE_THRESHOLD) return;
        if (columns == null) {
            log.warn("CsvSlicer: columns is null for sheet '{}', skipping slice", sheetName);
            return;
        }

        Path sliceDir = dataDir.resolve(StringUtils.sanitizeFileName(sheetName));
        if (Files.exists(sliceDir)) {
            // 先收集文件列表，关闭 Stream 后再逐个删除，避免 Windows 目录句柄冲突
            List<Path> toDelete;
            try (Stream<Path> files = Files.list(sliceDir)) {
                toDelete = files.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path p : toDelete) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Failed to delete old slice: {}", p, e);
                }
            }
        }
        Files.createDirectories(sliceDir);

        int totalChunks = (allRows.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("version", "1.0");
        index.put("name", StringUtils.sanitizeFileName(sheetName) + ".csv");
        index.put("total_rows", allRows.size());
        index.put("chunk_size", CHUNK_SIZE);
        index.put("columns", columns);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            int startRow = i * CHUNK_SIZE + 1;
            int endRow = Math.min(startRow + CHUNK_SIZE - 1, allRows.size());
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("file", String.format("chunk_%04d.csv", i + 1));
            chunk.put("row_range", List.of(startRow, endRow));
            chunks.add(chunk);
        }
        index.put("chunks", chunks);
        Files.writeString(sliceDir.resolve("index.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                StandardCharsets.UTF_8);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, allRows.size());
            Path chunkFile = sliceDir.resolve(String.format("chunk_%04d.csv", i + 1));

            try (BufferedWriter bw = Files.newBufferedWriter(chunkFile, StandardCharsets.UTF_8)) {
                for (int r = start; r < end; r++) {
                    bw.write(StringUtils.toCsvLine(allRows.get(r)));
                    bw.newLine();
                }
            }
        }
    }

}
