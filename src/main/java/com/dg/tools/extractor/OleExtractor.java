package com.dg.tools.extractor;

import org.apache.poi.poifs.filesystem.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class OleExtractor {

    private OleExtractor() {}

    public static Map<String, byte[]> extract(byte[] oleData) {
        if (oleData == null || oleData.length == 0) {
            return Collections.emptyMap();
        }
        try {
            return doExtract(oleData);
        } catch (Exception e) {
            // If not a valid OLE2 container, try as raw Ole10Native stream
            Map<String, byte[]> raw = tryRawOle10Native(oleData);
            if (!raw.isEmpty()) return raw;
            return Collections.emptyMap();
        }
    }

    private static Map<String, byte[]> tryRawOle10Native(byte[] data) {
        String fileName = tryExtractOle10NativeName(data);
        if (fileName == null || fileName.isEmpty()) return Collections.emptyMap();
        byte[] content = tryExtractOle10NativeContent(data);
        if (content == null || content.length == 0) return Collections.emptyMap();
        return Map.of(fileName, content);
    }

    private static Map<String, byte[]> doExtract(byte[] oleData) throws IOException {
        Map<String, byte[]> result = new HashMap<>();

        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(oleData))) {
            DirectoryNode root = fs.getRoot();

            // Try Ole10Native entries first (embedded OLE objects from Office docs)
            extractOle10Entries(root, result);

            // If no Ole10Native found, try all document entries as raw binary
            if (result.isEmpty()) {
                extractAllDocuments(root, "", result);
            }
        }

        return result;
    }

    private static void extractOle10Entries(DirectoryNode dir, Map<String, byte[]> result) throws IOException {
        for (Entry entry : dir) {
            if (entry instanceof DocumentNode docNode) {
                String name = entry.getName();
                // Ole10Native streams contain embedded file data
                try (DocumentInputStream dis = new DocumentInputStream(docNode)) {
                    byte[] data = dis.readAllBytes();

                    // Try to parse as Ole10Native to get original filename and content
                    String fileName = tryExtractOle10NativeName(data);
                    if (fileName != null && !fileName.isEmpty()) {
                        byte[] content = tryExtractOle10NativeContent(data);
                        if (content != null && content.length > 0) {
                            result.put(fileName, content);
                        }
                    }
                }
            } else if (entry instanceof DirectoryNode subDir) {
                extractOle10Entries(subDir, result);
            }
        }
    }

    private static void extractAllDocuments(DirectoryNode dir, String prefix, Map<String, byte[]> result) throws IOException {
        for (Entry entry : dir) {
            String path = prefix.isEmpty() ? entry.getName() : prefix + "/" + entry.getName();
            if (entry instanceof DocumentNode docNode) {
                try (DocumentInputStream dis = new DocumentInputStream(docNode)) {
                    byte[] data = dis.readAllBytes();
                    if (data.length > 0) {
                        result.put(path, data);
                    }
                }
            } else if (entry instanceof DirectoryNode subDir) {
                extractAllDocuments(subDir, path, result);
            }
        }
    }

    private static String tryExtractOle10NativeName(byte[] data) {
        if (data == null || data.length < 4) return null;
        // Ole10Native format: 4-byte size + filename (null-terminated ASCII) + file content
        // Find the null terminator within first 256 bytes
        int maxScan = Math.min(data.length, 260);
        int start = 0;
        for (int i = start; i < maxScan; i++) {
            if (data[i] == 0) {
                if (i > start) {
                    return new String(data, start, i - start, java.nio.charset.StandardCharsets.US_ASCII).trim();
                }
                break;
            }
        }
        return null;
    }

    private static byte[] tryExtractOle10NativeContent(byte[] data) {
        if (data == null || data.length < 4) return null;
        // Find filename null terminator, content is everything after it
        int maxScan = Math.min(data.length, 260);
        for (int i = 0; i < maxScan; i++) {
            if (data[i] == 0) {
                int contentStart = i + 1;
                // Try to detect a 4-byte length prefix
                if (contentStart + 4 <= data.length) {
                    int contentLength = ((data[contentStart] & 0xFF)
                            | ((data[contentStart + 1] & 0xFF) << 8)
                            | ((data[contentStart + 2] & 0xFF) << 16)
                            | ((data[contentStart + 3] & 0xFF) << 24));
                    // Validate: if length is reasonable, use it
                    if (contentLength > 0 && contentStart + 4 + contentLength <= data.length
                            && contentLength < data.length) {
                        byte[] content = new byte[contentLength];
                        System.arraycopy(data, contentStart + 4, content, 0, contentLength);
                        return content;
                    }
                }
                // No valid length prefix — take everything after null terminator
                if (contentStart < data.length) {
                    byte[] content = new byte[data.length - contentStart];
                    System.arraycopy(data, contentStart, content, 0, content.length);
                    return content;
                }
                break;
            }
        }
        return null;
    }
}
