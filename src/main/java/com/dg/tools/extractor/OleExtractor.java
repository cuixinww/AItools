package com.dg.tools.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.filesystem.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * OLE2 二进制容器提取器。
 *
 * 从 Office 文档（.doc / .xls / .ppt，即 OLE2 复合文档）以及
 * 其内嵌的 OLE 对象中，提取出真实的用户文件（如嵌入的 Word/Excel 文档、附件等）。
 * <p>解析策略：
 * <ol>
 *   <li>优先尝试把内部流当作 Ole10Native 结构解析 —— Office 内嵌对象最常用的封装格式</li>
 *   <li>若找不到任何 Ole10Native 条目，把所有「非内部保留」的文档流整体作为原始二进制返回</li>
 *   <li>若输入根本不是合法 OLE2 容器，尝试把整段字节当作裸 Ole10Native 来解析</li>
 * </ol>
 *
 * @see DocxHandler#unpackOleEmbeddings
 * @see OleExtractor
 */
@Slf4j
public class OleExtractor {

    /** 递归展开的最大深度，防止 OLE 目录树过深导致栈溢出。 */
    private static final int MAX_DEPTH = 10;

    /**
     * OLE2 内部保留流名称集合。
     * 这些是复合文档的账本/索引流，不是用户文件，应跳过。
     */
    private static final Set<String> INTERNAL_STREAMS = Set.of(
            "WordDocument", "1Table", "0Table", "Data",
            "ObjectPool", "CompObj", "ObjInfo",
            "\u0001CompObj", "\u0003ObjInfo"
    );

    /** 工具类，禁止外部实例化。 */
    private OleExtractor() {}

    /**
     * 从一段 OLE 数据中提取全部内嵌文件。
     * <p>入口方法：先尝试正常 OLE2 容器解析 → 失败后回退到裸 Ole10Native 解析 → 仍失败返回空 Map。
     *
     * @param oleData OLE 容器或 Ole10Native 的原始字节
     * @return 映射表：key 为文件名（含 OLE 路径前缀以防同名冲突），value 为文件内容
     */
    public static Map<String, byte[]> extract(byte[] oleData) {
        if (oleData == null || oleData.length == 0) {
            return Collections.emptyMap();
        }
        try {
            return doExtract(oleData);
        } catch (Exception e) {
            // OLE2 容器解析失败 → 回退到裸 Ole10Native 解析
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
     *
     * @param dir    当前目录节点
     * @param path   OLE 路径前缀（用于 key 消歧）
     * @param result 结果累加器
     * @param depth  当前递归深度
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
                            // 去掉 OLE2 控制字符前缀（如 \x01Ole10Native → Ole10Native）
                            String cleanPath = entryPath.replaceAll("^[\u0001-\u0005]", "");
                            String key = cleanPath + "/" + fileName;
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

    /**
     * 判断给定的流名称是否为 OLE2 内部保留流。
     * 包括：INTERNAL_STREAMS 集合中的已知名称，以及以控制字符（0x01-0x05）开头的属性流。
     */
    private static boolean isInternalStream(String name) {
        if (name == null || name.isEmpty()) return true;
        // 仅过滤 OLE2 属性流（\u0005SummaryInformation 等），不过滤 \u0001Ole10Native 等数据流
        if (name.charAt(0) == 0x05) return true;
        return INTERNAL_STREAMS.contains(name);
    }

    /**
     * 确定 Ole10Native 数据中 label（文件名等价物）的起始偏移量。
     * <p>标准 MS 格式：{@code [totalSize(4)][flags1(2)][label\0][fileName\0]...}
     * <br>简化格式：{@code [totalSize(4)][filename\0]...}
     */
    private static int ole10NativeLabelOffset(byte[] data) {
        if (data.length >= 6) {
            int flags1 = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
            // flags1 == 2 表示标准格式，且 label 从偏移 6 开始（必须是可打印字符）
            if (flags1 == 2 && data[6] >= 0x20 && data[6] < 0x7F) {
                return 6;
            }
        }
        return 4;
    }

    /**
     * 从 Ole10Native 字节流中解析原始文件名（label）。
     * 考虑标准 MS Ole10Native 格式中 flags1 字段的影响。
     */
    private     static String tryExtractOle10NativeName(byte[] data) {
        if (data == null || data.length < 4) return null;
        int start = ole10NativeLabelOffset(data);
        int maxScan = Math.min(data.length, start + 256);
        for (int i = start; i < maxScan; i++) {
            if (data[i] == 0) {
                if (i > start) {
                    return decodeOleLabel(data, start, i - start);
                }
                break;
            }
        }
        return null;
    }

    private static String decodeOleLabel(byte[] bytes, int offset, int length) {
        String cp936 = new String(bytes, offset, length, Charset.forName("GBK"));
        return cp936.trim();
    }

    /**
     * 从 Ole10Native 字节流中解析文件内容。
     * <p>Ole10Native 真实格式（[MS-OLEDS] §2.3.3）：
     * <pre>
     *   nativeSize(4LE) + fileName(NUL) + srcPath(NUL) + tmpPath(NUL)
     *   + nativeDataSize(4LE) + content
     * </pre>
     * 部分简化场景只有：nativeSize(4LE) + fileName(NUL) + content。
     * <p>解析策略：
     * <ol>
     *   <li>策略 1：按完整格式解析（跳过 srcPath + tmpPath，读取 nativeDataSize，切出 content）</li>
     *   <li>策略 2：简化格式回退（NUL 后的 4 字节长度探测 / 全部剩余字节）</li>
     * </ol>
     */
    private static byte[] tryExtractOle10NativeContent(byte[] data) {
        if (data == null || data.length < 4) return null;
        int start = ole10NativeLabelOffset(data);

        // 标准 MS 格式（start == 6）：label 之后的格式不同，使用 POI 原生解析器
        if (start == 6) {
            byte[] content = tryExtractStandardContent(data);
            if (content != null) return content;
            // POI 解析失败时回退到 nameEnd 之后的通用逻辑
        }

        int maxScan = Math.min(data.length, start + 256);
        for (int i = start; i < maxScan; i++) {
            if (data[i] == 0) {
                int nameEnd = i;

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
     * 使用 POI 的 {@link Ole10Native} 解析器提取标准格式的数据缓冲区。
     */
    private static byte[] tryExtractStandardContent(byte[] data) {
        try {
            Ole10Native ole10 = new Ole10Native(data, 0);
            return ole10.getDataBuffer();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按完整 Ole10Native 格式解析：
     *   fileName + NUL + srcPath(NUL) + tmpPath(NUL) + nativeDataSize(4LE) + content
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
        // 没有合法长度前缀 — 取 NUL 之后的全部内容
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
