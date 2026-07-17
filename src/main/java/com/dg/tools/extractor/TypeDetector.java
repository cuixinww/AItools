package com.dg.tools.extractor;

import org.apache.tika.Tika;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;

import java.util.Map;

public class TypeDetector {

    private static final Map<String, String> MIME_TO_EXTENSION = Map.ofEntries(
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/zip", "zip"),
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("image/tiff", "tiff"),
            Map.entry("image/webp", "webp")
    );

    private final Tika tika = new Tika();

    public String detectExtension(byte[] data, String hintFileName) {
        if (data == null || data.length == 0) {
            return fallbackFromName(hintFileName);
        }

        String mime = detectMimeType(data);

        // If Tika confidently detects a known type, use it
        String ext = MIME_TO_EXTENSION.get(mime);
        if (ext != null) {
            return ext;
        }

        // If Tika returned generic types, trust the file extension first
        if ("application/octet-stream".equals(mime) || "text/plain".equals(mime)
                || "application/zip".equals(mime)) {
            String fallback = fallbackFromName(hintFileName);
            if (fallback != null && !"bin".equals(fallback)) {
                return fallback;
            }
        }

        // Try Tika's own extension mapping
        try {
            MimeTypes allTypes = MimeTypes.getDefaultMimeTypes();
            MimeType mimeType = allTypes.forName(mime);
            if (mimeType != null) {
                String tikaExt = mimeType.getExtension();
                if (tikaExt != null && !tikaExt.isEmpty()) {
                    return tikaExt.replace(".", "");
                }
            }
        } catch (Exception ignored) {
        }

        return fallbackFromName(hintFileName);
    }

    public String detectMimeType(byte[] data) {
        if (data == null || data.length == 0) {
            return "application/octet-stream";
        }

        try {
            return tika.detect(data);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private String fallbackFromName(String hintFileName) {
        if (hintFileName == null) return "bin";
        int dot = hintFileName.lastIndexOf('.');
        if (dot >= 0 && dot < hintFileName.length() - 1) {
            return hintFileName.substring(dot + 1).toLowerCase();
        }
        return "bin";
    }
}
