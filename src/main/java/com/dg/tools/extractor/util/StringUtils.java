package com.dg.tools.extractor.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.StringJoiner;

public final class StringUtils {

    private StringUtils() {}

    public static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\u0400-\\u04FF\\u0600-\\u06FF\\uAC00-\\uD7AF_-]", "_")
                .replaceAll("_+", "_");
    }

    public static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public static String toCsvLine(List<String> row) {
        StringJoiner sj = new StringJoiner(",");
        for (String cell : row) {
            sj.add(escapeCsv(cell));
        }
        return sj.toString();
    }
}
