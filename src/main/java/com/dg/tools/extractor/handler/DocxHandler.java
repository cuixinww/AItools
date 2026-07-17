package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.OleExtractor;
import com.dg.tools.extractor.model.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class DocxHandler implements DocumentHandler {

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".docm");
    }

    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("docx", fileName);
        int position = 0;

        try (XWPFDocument doc = new XWPFDocument(is)) {
            // 1. Header / footer
            extractHeadersFooters(doc, result, position);
            position = result.getElements().size();

            // 2. Body paragraphs, tables, images
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    int extracted = extractParagraph(para, result, position);
                    position += extracted;
                } else if (element instanceof XWPFTable table) {
                    String md = tableToMarkdown(table);
                    result.addElement(new Element(position++, "table", md));
                }
            }

            // 3. Embedded files from relationships (non-OLE: xlsx, pdf, zip etc.)
            extractEmbedded(doc, result);

            // 4. OLE embedded files
            extractOleEmbeddings(doc, result);

        } catch (IOException e) {
            throw new RuntimeException("Failed to parse docx: " + fileName, e);
        }

        return result;
    }

    private int extractParagraph(XWPFParagraph para, ExtractionResult result, int position) {
        int count = 0;
        // Check for images in runs
        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            for (XWPFPicture pic : pictures) {
                XWPFPictureData picData = pic.getPictureData();
                String picFileName = picData.getFileName();
                if (picFileName == null || picFileName.isEmpty()) {
                    picFileName = "image_" + position + "." + picData.suggestFileExtension();
                }
                ImageFile imgFile = new ImageFile(picFileName, position,
                        picData.getData(), picData.suggestFileExtension());
                result.addImage(imgFile);
                count++;
            }
        }
        // Text content
        String text = para.getText();
        if (!text.isBlank()) {
            result.addElement(new Element(position + count, "paragraph", text.trim()));
            count++;
        }
        return count;
    }

    private void extractHeadersFooters(XWPFDocument doc, ExtractionResult result, int position) {
        try {
            int pos = position;
            for (XWPFHeader header : doc.getHeaderList()) {
                if (header == null) continue;
                for (IBodyElement element : header.getBodyElements()) {
                    if (element instanceof XWPFParagraph para) {
                        String text = para.getText();
                        if (!text.isBlank()) {
                            result.addElement(new Element(pos++, "header", text.trim()));
                        }
                    } else if (element instanceof XWPFTable table) {
                        String md = tableToMarkdown(table);
                        result.addElement(new Element(pos++, "header", md));
                    }
                }
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                if (footer == null) continue;
                for (IBodyElement element : footer.getBodyElements()) {
                    if (element instanceof XWPFParagraph para) {
                        String text = para.getText();
                        if (!text.isBlank()) {
                            result.addElement(new Element(pos++, "footer", text.trim()));
                        }
                    } else if (element instanceof XWPFTable table) {
                        String md = tableToMarkdown(table);
                        result.addElement(new Element(pos++, "footer", md));
                    }
                }
            }
        } catch (Exception e) {
            // silently skip — header/footer extraction is best-effort
        }
    }

    private String tableToMarkdown(XWPFTable table) {
        StringBuilder md = new StringBuilder();
        for (int r = 0; r < table.getRows().size(); r++) {
            XWPFTableRow row = table.getRow(r);
            List<String> cells = row.getTableCells().stream()
                    .map(XWPFTableCell::getText).map(String::trim).toList();
            md.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (r == 0) {
                md.append("| ").append(cells.stream().map(c -> "---")
                        .collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");
            }
        }
        return md.toString().trim();
    }

    private void extractEmbedded(XWPFDocument doc, ExtractionResult result) {
        try {
            OPCPackage pkg = doc.getPackage();
            for (PackagePart part : pkg.getParts()) {
                String partName = part.getPartName().getName();
                if (partName == null || !partName.contains("embeddings")) continue;

                String embeddedFileName = partName.substring(partName.lastIndexOf('/') + 1);
                if (embeddedFileName.isEmpty()) continue;

                try (InputStream partIs = part.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = partIs.read(buf)) != -1) baos.write(buf, 0, n);
                    byte[] fileBytes = baos.toByteArray();

                    int embedPosition = result.getElements().size();
                    result.addEmbedded(new EmbeddedFile(embeddedFileName, embedPosition, fileBytes));
                    result.addElement(new Element(embedPosition, "embed",
                            null, "file: " + embeddedFileName));
                }
            }
        } catch (Exception e) {
            // silently skip embedded extraction failures
        }
    }

    private void extractOleEmbeddings(XWPFDocument doc, ExtractionResult result) {
        try {
            OPCPackage pkg = doc.getPackage();
            for (PackagePart part : pkg.getParts()) {
                String partName = part.getPartName().getName();
                if (partName == null || !partName.contains("embeddings")) continue;

                String contentType = part.getContentType();
                // Check for OLE content types
                if (contentType == null) continue;
                if (!contentType.contains("oleObject") && !contentType.contains("ole")) continue;

                try (InputStream partIs = part.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = partIs.read(buf)) != -1) baos.write(buf, 0, n);
                    byte[] oleBytes = baos.toByteArray();

                    Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);
                    for (var entry : extracted.entrySet()) {
                        int embedPosition = result.getElements().size();
                        result.addEmbedded(new EmbeddedFile(entry.getKey(), embedPosition, entry.getValue()));
                        result.addElement(new Element(embedPosition, "embed",
                                null, "file: " + entry.getKey()));
                    }
                    // If OleExtractor returned nothing, still add raw blob for recursive handling
                    if (extracted.isEmpty() && oleBytes.length > 0) {
                        // Try as raw OLE2 container
                        String innerFileName = partName.substring(partName.lastIndexOf('/') + 1);
                        int embedPosition = result.getElements().size();
                        result.addEmbedded(new EmbeddedFile(innerFileName, embedPosition, oleBytes));
                        result.addElement(new Element(embedPosition, "embed",
                                null, "file: " + innerFileName));
                    }
                }
            }
        } catch (Exception e) {
            // silently skip
        }
    }
}
