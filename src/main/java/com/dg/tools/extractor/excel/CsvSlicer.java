package com.dg.tools.extractor.excel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
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
public class CsvSlicer {

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
     * @param columns   列名列表（用于 index.json）
     */
    public static void sliceIfNeeded(Path dataDir, String sheetName,
                                       List<List<String>> allRows, List<String> columns) {
        if (allRows.size() <= SLICE_THRESHOLD) return;

        Path sliceDir = dataDir.resolve(sanitizeFileName(sheetName));
        try {
            // 清理上次运行的旧切片文件
            if (Files.exists(sliceDir)) {
                try (Stream<Path> files = Files.list(sliceDir)) {
                    files.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
            Files.createDirectories(sliceDir);

            int totalChunks = (allRows.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;

            // 写 index.json（手工拼装，避免额外 JSON 依赖）
            StringBuilder index = new StringBuilder();
            index.append("{\n");
            index.append("  \"version\": \"1.0\",\n");
            index.append("  \"name\": \"").append(escapeJson(sanitizeFileName(sheetName))).append(".csv\",\n");
            index.append("  \"total_rows\": ").append(allRows.size()).append(",\n");
            index.append("  \"chunk_size\": ").append(CHUNK_SIZE).append(",\n");
            index.append("  \"columns\": [");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) index.append(", ");
                index.append("\"").append(escapeJson(columns.get(i))).append("\"");
            }
            index.append("],\n");
            index.append("  \"chunks\": [\n");
            for (int i = 0; i < totalChunks; i++) {
                // 1-based 行号，便于人类阅读
                int startRow = i * CHUNK_SIZE + 1;
                int endRow = Math.min(startRow + CHUNK_SIZE - 1, allRows.size());
                if (i > 0) index.append(",\n");
                index.append("    {\"file\": \"chunk_");
                index.append(String.format("%04d.csv", i + 1));
                index.append("\", \"row_range\": [").append(startRow).append(", ").append(endRow).append("]}");
            }
            index.append("\n  ]\n");
            index.append("}\n");
            Files.writeString(sliceDir.resolve("index.json"), index.toString(), StandardCharsets.UTF_8);

            // 写各切片文件
            for (int i = 0; i < totalChunks; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(start + CHUNK_SIZE, allRows.size());
                Path chunkFile = sliceDir.resolve(String.format("chunk_%04d.csv", i + 1));

                try (BufferedWriter bw = Files.newBufferedWriter(chunkFile, StandardCharsets.UTF_8)) {
                    for (int r = start; r < end; r++) {
                        List<String> row = allRows.get(r);
                        StringJoiner sj = new StringJoiner(",");
                        for (String cell : row) {
                            sj.add(escapeCsv(cell));
                        }
                        bw.write(sj.toString());
                        bw.newLine();
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to slice CSV for sheet: " + sheetName, e);
        }
    }

    /** 文件名清洗：保留字母/数字/中文等 unicode 文字/下划线/连字符，合并连续下划线。 */
    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\u0400-\\u04FF\\u0600-\\u06FF\\uAC00-\\uD7AF_-]", "_")
                .replaceAll("_+", "_");
    }

    /** CSV 单元格转义：含逗号/引号/换行的用双引号包裹并将内部引号转义为双引号。 */
    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** JSON 字符串转义：处理反斜杠、引号与控制字符。 */
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
