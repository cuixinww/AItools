package com.dg.tools.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.filesystem.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * OLE2 二进制容器提取器（OleExtractor）。
 *
 * 用途：从 Office 文档（.doc / .xls / .ppt，即 OLE2 复合文档）以及
 * 其内嵌的 OLE 对象中，提取出真实的用户文件（如嵌入的 Word/Excel 文档、附件等）。
 *
 * 两种解析策略：
 *   1) 优先尝试把内部流当作 Ole10Native 结构解析 —— 这是 Office 内嵌对象最常用的封装格式，
 *      其中包含原始文件名与原始文件内容；
 *   2) 若找不到任何 Ole10Native 条目，则把所有「非内部保留」的文档流整体作为原始二进制返回。
 *
 * 此外，当输入本身根本不是合法 OLE2 容器时，会尝试把整段字节当作裸 Ole10Native 来解析。
 */
@Slf4j
public class OleExtractor {

    /** 递归展开的最大深度，防止 OLE 目录树过深。 */
    private static final int MAX_DEPTH = 10;

    /** OLE2 内部保留流名称集合 —— 这些是复合文档的账本 / 索引流，不是用户文件，应跳过。 */
    private static final Set<String> INTERNAL_STREAMS = Set.of(
            "WordDocument", "1Table", "0Table", "Data",
            "ObjectPool", "CompObj", "ObjInfo"
    );

    /** 工具类，禁止外部实例化。 */
    private OleExtractor() {}

    /**
     * 从一段 OLE 数据中提取全部内嵌文件。
     *
     * @param oleData OLE 容器或 Ole10Native 的原始字节
     * @return 映射表：key 为文件名（OLE 路径前缀 + 原始文件名，避免同名冲突），value 为文件内容
     */
    public static Map<String, byte[]> extract(byte[] oleData) {
        if (oleData == null || oleData.length == 0) {
            return Collections.emptyMap();
        }
        try {
            return doExtract(oleData);
        } catch (Exception e) {
            log.warn("OLE extraction failed, trying raw Ole10Native fallback", e);
            Map<String, byte[]> raw = tryRawOle10Native(oleData);
            if (!raw.isEmpty()) return raw;
            return Collections.emptyMap();
        }
    }

    /**
     * 把整段数据当作裸 Ole10Native 解析（仅当它不是 OLE2 容器时作为兜底）。
     */
    private static Map<String, byte[]> tryRawOle10Native(byte[] data) {
        String fileName = tryExtractOle10NativeName(data);
        if (fileName == null || fileName.isEmpty()) return Collections.emptyMap();
        byte[] content = tryExtractOle10NativeContent(data);
        if (content == null || content.length == 0) return Collections.emptyMap();
        return Map.of(fileName, content);
    }

    /**
     * 真正的 OLE2 复合文档解析。
     *
     * @throws IOException 当容器无法打开或遍历失败时
     */
    private static Map<String, byte[]> doExtract(byte[] oleData) throws IOException {
        Map<String, byte[]> result = new HashMap<>();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(oleData))) {
            DirectoryNode root = fs.getRoot();

            // 先尝试 Ole10Native 条目（Office 文档最常见的内嵌对象封装）
            extractOle10Entries(root, "", result, 0);

            // 若一个 Ole10Native 都没找到，则退而求其次，把所有文档流当作原始二进制返回
            if (result.isEmpty()) {
                extractAllDocuments(root, "", result, 0);
            }
        }

        return result;
    }

    /**
     * 递归遍历 OLE 目录树，定位并解析每个 Ole10Native 条目。
     * 同名的文件来自不同内嵌对象时，用 OLE 路径前缀做 key 以避免冲突。
     */
    private static void extractOle10Entries(DirectoryNode dir, String path, Map<String, byte[]> result, int depth) throws IOException {
        if (depth >= MAX_DEPTH) return;
        for (Entry entry : dir) {
            String entryPath = path.isEmpty() ? entry.getName() : path + "/" + entry.getName();
            if (entry instanceof DocumentNode docNode) {
                try (DocumentInputStream dis = new DocumentInputStream(docNode)) {
                    byte[] data = dis.readAllBytes();

                    // 尝试把该流解析为 Ole10Native，取出原始文件名与内容
                    String fileName = tryExtractOle10NativeName(data);
                    if (fileName != null && !fileName.isEmpty()) {
                        byte[] content = tryExtractOle10NativeContent(data);
                        if (content != null && content.length > 0) {
                            // 用 OLE 路径前缀区分来自不同内嵌对象的同名文件
                            String key = entryPath + "/" + fileName;
                            result.put(key, content);
                        }
                    }
                }
            } else if (entry instanceof DirectoryNode subDir) {
                extractOle10Entries(subDir, entryPath, result, depth + 1);
            }
        }
    }

    /**
     * 兜底策略：当一个 Ole10Native 都没解析出来时，
     * 把所有「非内部保留」的文档流整体作为原始二进制返回。
     */
    private static void extractAllDocuments(DirectoryNode dir, String prefix, Map<String, byte[]> result, int depth) throws IOException {
        if (depth >= MAX_DEPTH) return;
        for (Entry entry : dir) {
            String path = prefix.isEmpty() ? entry.getName() : prefix + "/" + entry.getName();
            if (entry instanceof DocumentNode docNode) {
                // 跳过 OLE2 内部账本流
                if (isInternalStream(entry.getName())) continue;
                try (DocumentInputStream dis = new DocumentInputStream(docNode)) {
                    byte[] data = dis.readAllBytes();
                    if (data.length > 0) {
                        result.put(path, data);
                    }
                }
            } else if (entry instanceof DirectoryNode subDir) {
                extractAllDocuments(subDir, path, result, depth + 1);
            }
        }
    }

    /** 过滤掉 OLE2 内部账本流（不是用户文件）。 */
    private static boolean isInternalStream(String name) {
        if (name == null || name.isEmpty()) return true;
        // OLE2 属性流名称以控制字符（0x01-0x05）开头
        if (name.charAt(0) >= 0x01 && name.charAt(0) <= 0x05) return true;
        return INTERNAL_STREAMS.contains(name);
    }

    /**
     * 从 Ole10Native 字节流中解析原始文件名。
     * 格式：4 字节长度 + 文件名（以 NUL 结尾）+ 文件内容。
     * 这里跳过开头 4 字节长度字段，扫描到第一个 NUL 作为文件名结束。
     */
    private static String tryExtractOle10NativeName(byte[] data) {
        if (data == null || data.length < 4) return null;
        // Ole10Native 格式：4 字节长度 + 文件名（NUL 结尾）+ 文件内容
        // 跳过开头的 4 字节长度字段
        int maxScan = Math.min(data.length, 260);
        int start = 4;
        for (int i = start; i < maxScan; i++) {
            if (data[i] == 0) {
                if (i > start) {
                    return new String(data, start, i - start, StandardCharsets.ISO_8859_1).trim();
                }
                break;
            }
        }
        return null;
    }

    /**
     * 从 Ole10Native 字节流中解析文件内容。
     *
     * Ole10Native 真实格式（[MS-OLEDS] §2.3.3）：
     *   nativeSize(4LE) + fileName(NUL) + srcPath(NUL) + tmpPath(NUL)
     *   + nativeDataSize(4LE) + content
     *
     * 但部分简化场景只有：nativeSize(4LE) + fileName(NUL) + content。
     *
     * 解析策略：
     *   1) 优先按完整格式解析（跳过 srcPath + tmpPath，读取 nativeDataSize，切出 content）；
     *   2) 若完整格式失败，回退到简化格式（NUL 后的 4 字节长度探测 / 全部剩余字节）。
     */
    private static byte[] tryExtractOle10NativeContent(byte[] data) {
        if (data == null || data.length < 4) return null;
        int maxScan = Math.min(data.length, 260);
        for (int i = 4; i < maxScan; i++) {
            if (data[i] == 0) {
                int nameEnd = i; // 文件名 NUL 位置

                // 策略 1：按完整 Ole10Native 格式解析
                byte[] content = tryExtractFullFormat(data, nameEnd);
                if (content != null) return content;

                // 策略 2：简化格式回退
                return tryExtractSimplifiedFormat(data, nameEnd);
            }
        }
        return null;
    }

    /**
     * 按完整 Ole10Native 格式解析：
     *   fileName + NUL + srcPath + NUL + tmpPath + NUL + nativeDataSize(4LE) + content
     */
    private static byte[] tryExtractFullFormat(byte[] data, int nameEnd) {
        // 跳过 srcPath（到下一个 NUL）
        int srcEnd = skipNulTerminatedString(data, nameEnd + 1);
        if (srcEnd < 0) return null;

        // 跳过 tmpPath（到下一个 NUL）
        int tmpEnd = skipNulTerminatedString(data, srcEnd + 1);
        if (tmpEnd < 0) return null;

        // 读取 4 字节 nativeDataSize（little-endian）
        int sizeStart = tmpEnd + 1;
        if (sizeStart + 4 > data.length) return null;
        int contentLength = ((data[sizeStart] & 0xFF)
                | ((data[sizeStart + 1] & 0xFF) << 8)
                | ((data[sizeStart + 2] & 0xFF) << 16)
                | ((data[sizeStart + 3] & 0xFF) << 24));

        int contentStart = sizeStart + 4;
        if (contentLength > 0 && contentStart + contentLength <= data.length
                && contentLength < data.length) {
            byte[] result = new byte[contentLength];
            System.arraycopy(data, contentStart, result, 0, contentLength);
            return result;
        }
        return null;
    }

    /**
     * 简化格式回退：NUL 之后尝试 4 字节长度前缀，否则取全部剩余字节。
     */
    private static byte[] tryExtractSimplifiedFormat(byte[] data, int nameEnd) {
        int contentStart = nameEnd + 1;
        // 尝试探测一个 4 字节长度前缀
        if (contentStart + 4 <= data.length) {
            int contentLength = ((data[contentStart] & 0xFF)
                    | ((data[contentStart + 1] & 0xFF) << 8)
                    | ((data[contentStart + 2] & 0xFF) << 16)
                    | ((data[contentStart + 3] & 0xFF) << 24));
            // 合法性校验：长度合理且不越界
            if (contentLength > 0 && contentStart + 4 + contentLength <= data.length
                    && contentLength < data.length) {
                byte[] result = new byte[contentLength];
                System.arraycopy(data, contentStart + 4, result, 0, contentLength);
                return result;
            }
        }
        // 没有合法长度前缀 —— 取 NUL 之后的全部内容
        if (contentStart < data.length) {
            byte[] result = new byte[data.length - contentStart];
            System.arraycopy(data, contentStart, result, 0, result.length);
            return result;
        }
        return null;
    }

    /** 从指定位置开始扫描，找到下一个 NUL（0x00）的位置；找不到返回 -1。 */
    private static int skipNulTerminatedString(byte[] data, int start) {
        for (int i = start; i < data.length; i++) {
            if (data[i] == 0) return i;
        }
        return -1;
    }
}
