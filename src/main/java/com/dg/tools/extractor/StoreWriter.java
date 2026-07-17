package com.dg.tools.extractor;

import com.dg.tools.extractor.model.*;
import com.dg.tools.extractor.model.LargeTableInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

public class StoreWriter {

    static final int CHUNK_SIZE = 50;

    public void writeBody(Path docDir, String mdFileName, ExtractionResult result,
                          int seq, String sourceFile, String parentInfo) throws IOException {
        Files.createDirectories(docDir);
        Path bodyFile = docDir.resolve(mdFileName);

        try (PrintWriter pw = new PrintWriter(bodyFile.toFile(), StandardCharsets.UTF_8)) {
            pw.println("# DOC: " + seq);
            pw.println("# SOURCE: " + sourceFile);
            if (parentInfo != null) {
                pw.println("# PARENT: " + parentInfo);
            }
            pw.println();

            for (Element elem : result.getElements()) {
                writeElement(pw, elem);
            }

            for (LargeTableInfo lt : result.getLargeTables()) {
                pw.println("# POS: " + lt.getPosition() + " | TYPE: data_ref | " +
                        "schema: " + lt.getSchema() + " | rows: " + lt.getRowCount() +
                        " | file: data/" + sanitizeFileName(lt.getSheetName()) + ".csv");
                if (lt.getPreview() != null && !lt.getPreview().isEmpty()) {
                    pw.println(lt.getPreview());
                }
                pw.println();
            }

            for (ImageFile img : result.getImages()) {
                pw.println("# POS: " + img.getPosition() + " | TYPE: image | " +
                        "file: media/" + sanitizeFileName(img.getFileName()));
                pw.println();
            }
        }
    }

    public void writeChunks(Path docDir, String docDirName, String mdFileName, ExtractionResult result) throws IOException {
        int totalElements = result.getElements().size();
        int totalLargeTables = result.getLargeTables().size();
        int totalImages = result.getImages().size();

        Path chunksDir = docDir.resolve("chunks");
        Files.createDirectories(chunksDir);

        int totalChunks = (totalElements + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (totalChunks == 0) totalChunks = 1;

        // Write index.json
        StringBuilder index = new StringBuilder();
        index.append("{\n");
        index.append("  \"source\": \"").append(docDirName).append("/").append(mdFileName).append("\",\n");
        index.append("  \"total_elements\": ").append(totalElements).append(",\n");
        index.append("  \"total_large_tables\": ").append(totalLargeTables).append(",\n");
        index.append("  \"total_images\": ").append(totalImages).append(",\n");
        index.append("  \"chunk_size\": ").append(CHUNK_SIZE).append(",\n");
        index.append("  \"chunks\": [\n");

        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE - 1, totalElements - 1);
            String chunkFile = String.format("chunk_%04d.md", i + 1);

            boolean hasLargeTable = result.getLargeTables().stream()
                    .anyMatch(lt -> lt.getPosition() >= start && lt.getPosition() <= end);

            if (i > 0) index.append(",\n");
            index.append("    {");
            index.append("\"file\": \"").append(chunkFile).append("\", ");
            index.append("\"pos_range\": [").append(start).append(", ").append(end).append("], ");
            index.append("\"element_count\": ").append(end - start + 1).append(", ");
            index.append("\"includes_large_table\": ").append(hasLargeTable);
            index.append("}");
        }

        index.append("\n  ],\n");

        // large_tables array
        index.append("  \"large_tables\": [\n");
        for (int i = 0; i < totalLargeTables; i++) {
            LargeTableInfo lt = result.getLargeTables().get(i);
            String csvFile = sanitizeFileName(lt.getSheetName()) + ".csv";
            int chunkIdx = lt.getPosition() / CHUNK_SIZE;
            String inChunk = String.format("chunk_%04d.md", chunkIdx + 1);

            if (i > 0) index.append(",\n");
            index.append("    {");
            index.append("\"name\": \"").append(escapeJson(csvFile)).append("\", ");
            index.append("\"sheet\": \"").append(escapeJson(lt.getSheetName())).append("\", ");
            index.append("\"pos\": ").append(lt.getPosition()).append(", ");
            index.append("\"rows\": ").append(lt.getRowCount()).append(", ");
            index.append("\"file\": \"data/").append(escapeJson(csvFile)).append("\", ");
            index.append("\"in_chunk\": \"").append(inChunk).append("\"");
            index.append("}");
        }
        index.append("\n  ]\n");

        index.append("}\n");
        Files.writeString(chunksDir.resolve("index.json"), index.toString(), StandardCharsets.UTF_8);

        // Write chunk files
        List<Element> elements = result.getElements();
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, elements.size());
            String chunkFile = String.format("chunk_%04d.md", i + 1);

            try (PrintWriter pw = new PrintWriter(
                    chunksDir.resolve(chunkFile).toFile(), StandardCharsets.UTF_8)) {
                pw.println("# Chunk " + (i + 1) + " of " + totalChunks);
                pw.println("# Source: " + docDirName + "/" + mdFileName);
                pw.println("# Pos range: " + start + " - " + (end - 1));
                pw.println();
                for (int j = start; j < end; j++) {
                    writeElement(pw, elements.get(j));
                }
            }
        }
    }

    public void writeSourceFile(Path docDir, byte[] rawBytes, String fileName) throws IOException {
        Files.createDirectories(docDir);
        Files.write(docDir.resolve(fileName), rawBytes);
    }

    public void writeMedia(Path docDir, ExtractionResult result) throws IOException {
        if (result.getImages().isEmpty()) return;
        Path mediaDir = docDir.resolve("media");
        Files.createDirectories(mediaDir);

        for (ImageFile img : result.getImages()) {
            Path imgFile = mediaDir.resolve(img.getFileName());
            Files.write(imgFile, img.getData());
        }
    }

    public void writeLargeTables(Path docDir, ExtractionResult result) throws IOException {
        if (result.getLargeTables().isEmpty()) return;
        Path dataDir = docDir.resolve("data");
        Files.createDirectories(dataDir);

        for (LargeTableInfo lt : result.getLargeTables()) {
            Path csvFile = dataDir.resolve(sanitizeFileName(lt.getSheetName()) + ".csv");
            try (PrintWriter pw = new PrintWriter(csvFile.toFile(), StandardCharsets.UTF_8)) {
                for (List<String> row : lt.getAllRows()) {
                    StringJoiner sj = new StringJoiner(",");
                    for (String cell : row) {
                        sj.add(escapeCsv(cell));
                    }
                    pw.println(sj);
                }
            }
        }
    }

    public static String buildManifestJson(String sessionId, String sourceFile,
                                            List<DocInfo> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"session_id\": \"").append(escapeJson(sessionId)).append("\",\n");
        sb.append("  \"source_file\": \"").append(escapeJson(sourceFile)).append("\",\n");
        sb.append("  \"docs\": [\n");
        for (int i = 0; i < docs.size(); i++) {
            DocInfo d = docs.get(i);
            sb.append("    {");
            sb.append("\"seq\": ").append(d.seq).append(", ");
            sb.append("\"dir\": \"").append(escapeJson(d.dir)).append("\", ");
            sb.append("\"source\": \"").append(escapeJson(d.source)).append("\", ");
            sb.append("\"source_copy\": \"").append(escapeJson(d.sourceCopy)).append("\", ");
            sb.append("\"type\": \"").append(escapeJson(d.type)).append("\", ");
            sb.append("\"element_count\": ").append(d.elementCount).append(", ");
            sb.append("\"data_ref_count\": ").append(d.dataRefCount).append(", ");
            sb.append("\"image_count\": ").append(d.imageCount);
            if (d.parentInfo != null) {
                sb.append(", \"parent\": \"").append(escapeJson(d.parentInfo)).append("\"");
            }
            sb.append("}");
            if (i < docs.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static class DocInfo {
        public final int seq;
        public final String dir;
        public final String source;
        public final String sourceCopy;
        public final String type;
        public final int elementCount;
        public final int dataRefCount;
        public final int imageCount;
        public final String parentInfo;

        public DocInfo(int seq, String dir, String source, String sourceCopy, String type,
                       int elementCount, int dataRefCount, int imageCount, String parentInfo) {
            this.seq = seq;
            this.dir = dir;
            this.source = source;
            this.sourceCopy = sourceCopy;
            this.type = type;
            this.elementCount = elementCount;
            this.dataRefCount = dataRefCount;
            this.imageCount = imageCount;
            this.parentInfo = parentInfo;
        }
    }

    private void writeElement(PrintWriter pw, Element elem) {
        StringBuilder header = new StringBuilder();
        header.append("# POS: ").append(elem.getPosition());
        header.append(" | TYPE: ").append(elem.getType());
        if (elem.getMetadata() != null && !elem.getMetadata().isEmpty()) {
            header.append(" | ").append(elem.getMetadata());
        }
        pw.println(header);
        if (elem.getContent() != null && !elem.getContent().isEmpty()) {
            pw.println(elem.getContent());
        }
        pw.println();
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
