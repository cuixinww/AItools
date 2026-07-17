package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.*;
import com.dg.tools.extractor.model.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

@Component
public class RecursiveExtractor {

    private static final int MAX_DEPTH = 10;

    private List<DocumentHandler> handlers;
    private final PositionTracker positionTracker = new PositionTracker();
    private final StoreWriter storeWriter = new StoreWriter();
    private final TypeDetector typeDetector = new TypeDetector();
    private final List<StoreWriter.DocInfo> docInfos = new ArrayList<>();
    private final Set<String> usedDirNames = new HashSet<>();
    private String sessionId;
    private Path baseDir;
    private int docSeq;

    public RecursiveExtractor() {
        this.handlers = List.of(
                new DocxHandler(),
                new DocHandler(),
                new ExcelHandler(),
                new PdfHandler(),
                new ZipHandler(),
                new ImageHandler()
        );
    }

    public void setHandlers(DocumentHandler... handlerArray) {
        this.handlers = List.of(handlerArray);
    }

    public void extract(InputStream is, String fileName, String sessionId, Path outputDir) {
        this.sessionId = sessionId;
        this.baseDir = outputDir;
        this.positionTracker.reset();
        this.docInfos.clear();
        this.usedDirNames.clear();
        this.docSeq = 0;

        doExtract(is, fileName, null);
        writeManifest();
    }

    private void doExtract(InputStream is, String fileName, String parentInfo) {
        int depth = positionTracker.getCurrentLevel();
        if (depth >= MAX_DEPTH) return;

        byte[] rawBytes;
        try {
            rawBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read input stream for: " + fileName, e);
        }

        DocumentHandler handler = selectHandler(rawBytes, fileName);
        if (handler == null) return;

        ExtractionResult result = handler.extract(new ByteArrayInputStream(rawBytes), fileName);

        int seq = docSeq++;
        String baseName = fileNameToBaseName(fileName);
        String docDirName = allocateDirName(baseName);
        Path docDir = baseDir.resolve(docDirName);
        String mdFileName = baseName + ".md";

        try {
            storeWriter.writeBody(docDir, mdFileName, result, seq, fileName, parentInfo);
            storeWriter.writeSourceFile(docDir, rawBytes, fileName);
            storeWriter.writeMedia(docDir, result);
            storeWriter.writeLargeTables(docDir, result);
            storeWriter.writeChunks(docDir, docDirName, mdFileName, result);

            docInfos.add(new StoreWriter.DocInfo(
                    seq, docDirName, fileName, docDirName + "/" + fileName, result.getFileType(),
                    result.getElements().size(),
                    result.getLargeTables().size(),
                    result.getImages().size(),
                    parentInfo != null ? parentInfo : "root"
            ));

            // Recurse into embedded files
            for (EmbeddedFile emb : result.getEmbeddedFiles()) {
                positionTracker.enterEmbedding(emb.getPosition());
                doExtract(new ByteArrayInputStream(emb.getData()),
                        emb.getFileName(),
                        docDirName + ", pos=" + emb.getPosition());
                positionTracker.exitEmbedding();
            }

            // Process images as separate documents
            for (ImageFile img : result.getImages()) {
                positionTracker.enterEmbedding(img.getPosition());
                doExtract(new ByteArrayInputStream(img.getData()),
                        img.getFileName(),
                        docDirName + ", pos=" + img.getPosition());
                positionTracker.exitEmbedding();
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to write extraction for " + docDirName, e);
        }
    }

    private String allocateDirName(String baseName) {
        if (!usedDirNames.contains(baseName)) {
            usedDirNames.add(baseName);
            return baseName;
        }
        for (int i = 2; ; i++) {
            String candidate = baseName + "_" + i;
            if (!usedDirNames.contains(candidate)) {
                usedDirNames.add(candidate);
                return candidate;
            }
        }
    }

    static String fileNameToBaseName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "unknown";
        String name = fileName;
        // Strip extension
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        // Sanitize: keep alphanumeric, Chinese, dot, dash, underscore
        name = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5.\\-_]", "_");
        // Collapse consecutive underscores
        name = name.replaceAll("_+", "_");
        // Trim leading/trailing underscores and dots
        name = name.replaceAll("^[._]+|[._]+$", "");
        if (name.isEmpty()) name = "unknown";
        return name;
    }

    private DocumentHandler selectHandler(byte[] rawBytes, String fileName) {
        String detectedExt = typeDetector.detectExtension(rawBytes, fileName);

        for (DocumentHandler h : handlers) {
            if (h.supports(detectedExt) || h.supports("x." + detectedExt)) {
                return h;
            }
        }

        for (DocumentHandler h : handlers) {
            if (h.supports(fileName)) {
                return h;
            }
        }

        return null;
    }

    private void writeManifest() {
        String json = StoreWriter.buildManifestJson(sessionId,
                docInfos.isEmpty() ? "unknown" : docInfos.get(0).source,
                docInfos);
        try {
            java.nio.file.Files.writeString(baseDir.resolve("manifest.json"), json,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write manifest.json", e);
        }
    }
}
