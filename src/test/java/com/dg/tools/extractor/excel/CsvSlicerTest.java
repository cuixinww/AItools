package com.dg.tools.extractor.excel;

import com.dg.tools.extractor.model.LargeTableInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CsvSlicerTest {

    /** 构造 rows 行、cols 列的字符串数据（含表头行）。 */
    private List<List<String>> buildRows(int rows, int cols) {
        List<List<String>> data = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                row.add("r" + r + "c" + c);
            }
            data.add(row);
        }
        return data;
    }

    @Test
    void shouldNotSliceWhenBelowThreshold(@TempDir Path dir) throws Exception {
        List<List<String>> rows = buildRows(10, 3);
        CsvSlicer.sliceIfNeeded(dir, "小表", rows, List.of("A", "B", "C"));
        // 未达到阈值，不应生成切片目录
        assertThat(dir.resolve("小表")).doesNotExist();
    }

    @Test
    void shouldSliceWhenAboveThreshold(@TempDir Path dir) throws Exception {
        List<List<String>> rows = buildRows(520, 2);
        List<String> columns = List.of("列1", "列2");
        CsvSlicer.sliceIfNeeded(dir, "大表", rows, columns);

        Path sliceDir = dir.resolve("大表");
        assertThat(sliceDir).exists();
        assertThat(sliceDir.resolve("index.json")).exists();
        // 520 行 / 100 = 6 个切片
        assertThat(sliceDir.resolve("chunk_0001.csv")).exists();
        assertThat(sliceDir.resolve("chunk_0006.csv")).exists();
        assertThat(sliceDir.resolve("chunk_0007.csv")).doesNotExist();
    }

    @Test
    void shouldWriteCorrectChunkRowCounts(@TempDir Path dir) throws Exception {
        List<List<String>> rows = buildRows(550, 1);
        CsvSlicer.sliceIfNeeded(dir, "切片表", rows, List.of("C"));

        Path sliceDir = dir.resolve("切片表");
        // 550 行 / 100 = 6 个切片（最后一个 50 行）
        long c1 = Files.lines(sliceDir.resolve("chunk_0001.csv")).count();
        long c6 = Files.lines(sliceDir.resolve("chunk_0006.csv")).count();
        assertThat(c1).isEqualTo(100);
        assertThat(c6).isEqualTo(50);
    }

    @Test
    void shouldWriteIndexJsonWithColumnsAndTotalRows(@TempDir Path dir) throws Exception {
        List<List<String>> rows = buildRows(600, 2);
        CsvSlicer.sliceIfNeeded(dir, "索引表", rows, List.of("姓名", "年龄"));

        String index = Files.readString(dir.resolve("索引表").resolve("index.json"));
        // 注意：CsvSlicer 手工拼装的 JSON 为紧凑格式（无空格）
        assertThat(index).contains("\"total_rows\": 600");
        assertThat(index).contains("\"姓名\"");
        assertThat(index).contains("\"年龄\"");
        assertThat(index).contains("\"chunk_size\": 100");
    }

    @Test
    void shouldEscapeCsvSpecialChars(@TempDir Path dir) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(List.of("a,b", "c\"d", "e" + i));
        }
        CsvSlicer.sliceIfNeeded(dir, "特殊字符", rows, List.of("A", "B", "C"));

        String line = Files.readString(dir.resolve("特殊字符").resolve("chunk_0001.csv")).trim();
        // 含逗号/引号/换行的单元格应被双引号包裹
        assertThat(line).contains("\"a,b\"");
        assertThat(line).contains("\"c\"\"d\"");
    }

    @Test
    void shouldCleanStaleChunksBeforeRewrite(@TempDir Path dir) throws Exception {
        List<List<String>> rows = buildRows(1200, 1);
        // 先写一次（产生 chunk_0001..chunk_0012）
        CsvSlicer.sliceIfNeeded(dir, "重用表", rows, List.of("C"));
        // 再写一个较小的表到同名目录（600 行 → 6 个切片），旧切片应被清理
        List<List<String>> small = buildRows(600, 1);
        CsvSlicer.sliceIfNeeded(dir, "重用表", small, List.of("C"));

        Path sliceDir = dir.resolve("重用表");
        // 旧的 chunk_0007..chunk_0012 应被清理
        assertThat(sliceDir.resolve("chunk_0007.csv")).doesNotExist();
        assertThat(sliceDir.resolve("chunk_0006.csv")).exists();
    }

    @Test
    void shouldAcceptLargeTableInfoForCsvContent(@TempDir Path dir) throws Exception {
        // 用 LargeTableInfo 携带的数据也能被切片（sliceIfNeeded 只依赖 allRows/columns）
        List<List<String>> rows = buildRows(550, 2);
        LargeTableInfo lti = new LargeTableInfo("来自大表", 0,
                "列1 | 列2", "preview", 550, rows);
        CsvSlicer.sliceIfNeeded(dir, lti.getSheetName(), lti.getAllRows(),
                List.of(lti.getSchema().split(" \\| ")));

        assertThat(dir.resolve("来自大表").resolve("index.json")).exists();
    }
}
