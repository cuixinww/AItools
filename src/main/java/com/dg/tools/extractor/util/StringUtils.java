package com.dg.tools.extractor.util;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/**
 * 工具类：文件名清洗、CSV 转义、字节流读取。
 *
 * 所有方法均为静态无状态，线程安全。
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * 创建一个写入 UTF-8 BOM 的 BufferedWriter。
     * Excel 打开 CSV 时依赖 BOM 识别 UTF-8 编码，否则中文会显示乱码。
     *
     * @param file 目标文件路径
     * @return BufferedWriter（已写入 BOM）
     */
    public static BufferedWriter newBomWriter(Path file) throws IOException {
        BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        bw.write('\uFEFF');
        return bw;
    }

    /**
     * 从输入流读取全部字节到 byte[]。
     * 使用 8KB 缓冲区流式读取，避免一次性分配超大数组。
     *
     * @param is 输入流，方法不负责关闭该流（调用方管理生命周期）
     * @return 完整字节数组
     * @throws IOException 读取异常
     */
    public static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * 清洗文件名：移除非安全字符，替换连续下划线为单个。
     *
     * 保留字符集：ASCII 字母数字、中文(Unicode CJK)、西里尔字母、阿拉伯字母、韩文谚文、
     * 点号、下划线、连字符。其余字符统一替换为下划线，连续多个下划线合并为单个。
     * 末尾的点号会被截断以防止目录创建时被截断。
     *
     * @param name 原始文件名
     * @return 清洗后的文件名；null 或空返回 "unnamed"
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        // 将非安全字符替换为下划线
        String sanitized = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\u0400-\\u04FF\\u0600-\\u06FF\\uAC00-\\uD7AF._-]", "_")
                // 合并连续下划线
                .replaceAll("_+", "_");
        // 去掉末尾点号（避免文件名以 "." 结尾导致某些文件系统问题）
        if (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }

    /**
     * 对单个 CSV 单元格值做引号转义。
     * 如果值中包含逗号、双引号、换行符中的任意一个，则用双引号包裹并将内部双引号翻倍。
     *
     * @param value 原始值
     * @return 转义后的 CSV 单元格字符串
     */
    public static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 将一行数据列表转换为合规的 CSV 单行字符串。
     *
     * @param row 单元格值列表
     * @return 逗号分隔的 CSV 行字符串
     */
    public static String toCsvLine(List<String> row) {
        if (row == null) {
            return "";
        }
        StringJoiner sj = new StringJoiner(",");
        for (String cell : row) {
            sj.add(escapeCsv(cell));
        }
        return sj.toString();
    }
}
