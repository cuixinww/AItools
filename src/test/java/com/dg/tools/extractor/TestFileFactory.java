package com.dg.tools.extractor;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TestFileFactory {

    // ========== Simple docx ==========

    public static byte[] createSimpleDocx(String... paragraphs) throws IOException {
        XWPFDocument doc = new XWPFDocument();
        for (String p : paragraphs) {
            doc.createParagraph().createRun().setText(p);
        }
        return toBytes(doc);
    }

    public static byte[] createDocxWithTables(String[] paragraphs, String[][]... tableRows) throws IOException {
        XWPFDocument doc = new XWPFDocument();
        for (String p : paragraphs) {
            doc.createParagraph().createRun().setText(p);
        }
        for (String[][] rows : tableRows) {
            XWPFTable table = doc.createTable(rows.length, rows[0].length);
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length; c++) {
                    table.getRow(r).getCell(c).setText(rows[r][c]);
                }
            }
        }
        return toBytes(doc);
    }

    // ========== Docx with image ==========

    public static byte[] createDocxWithImage() throws IOException {
        try {
            XWPFDocument doc = new XWPFDocument();
            doc.createParagraph().createRun().setText("Before image");
            XWPFParagraph imgPara = doc.createParagraph();
            XWPFRun run = imgPara.createRun();
            try (InputStream pngStream = new ByteArrayInputStream(MINIMAL_PNG)) {
                run.addPicture(pngStream,
                        XWPFDocument.PICTURE_TYPE_PNG,
                        "image1.png",
                        org.apache.poi.util.Units.toEMU(100),
                        org.apache.poi.util.Units.toEMU(100));
            }
            doc.createParagraph().createRun().setText("After image");
            return toBytes(doc);
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IOException("Failed to create docx with image", e);
        }
    }

    // ========== Simple Excel ==========

    public static byte[] createSimpleExcel(String sheetName, String[]... rows) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = sheetName != null ? wb.createSheet(sheetName) : wb.createSheet();
        for (int r = 0; r < rows.length; r++) {
            var row = sheet.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                row.createCell(c).setCellValue(rows[r][c]);
            }
        }
        return toBytes(wb);
    }

    public static byte[] createLargeExcel(String sheetName, int rowCount, int colCount) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet(sheetName);
        for (int r = 0; r < rowCount; r++) {
            var row = sheet.createRow(r);
            for (int c = 0; c < colCount; c++) {
                row.createCell(c).setCellValue("val_" + r + "_" + c);
            }
        }
        return toBytes(wb);
    }

    // ========== Simple PDF ==========

    public static byte[] createSimplePdf(String... lines) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(50, 700);
            for (String line : lines) {
                cs.showText(line);
                cs.newLineAtOffset(0, -20);
            }
            cs.endText();
        }
        return toBytes(doc);
    }

    // ========== Simple ZIP ==========

    public static byte[] createSimpleZip(byte[]... fileBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < fileBytes.length; i++) {
                zos.putNextEntry(new ZipEntry("file_" + i + ".bin"));
                zos.write(fileBytes[i]);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    // ========== Docx with embedded files ==========

    public static byte[] createDocxWithEmbedded(EmbeddedEntry... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write(buildContentTypesXml(entries).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("_rels/.rels"));
            zos.write(buildTopRels().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zos.write(buildRelationsXml(entries).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(buildDocumentXml().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            for (EmbeddedEntry e : entries) {
                zos.putNextEntry(new ZipEntry("word/embeddings/" + e.fileName));
                zos.write(e.content);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    public static class EmbeddedEntry {
        public final String fileName;
        public final String contentType;
        public final byte[] content;

        public EmbeddedEntry(String fileName, String contentType, byte[] content) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.content = content;
        }
    }

    // ========== Docx with OLE (.docx embedding native Office objects) ==========

    public static byte[] createDocxWithOleEmbedding() throws IOException {
        // Build a minimal OLE2 stream wrapping some recognizable content (a docx)
        byte[] innerDocx = createSimpleDocx("OLE embedded content");
        byte[] oleBytes = wrapAsOle10Native("embedded.docx", innerDocx);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Default Extension=\"bin\" ContentType=\"application/vnd.openxmlformats-officedocument.oleObject\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("_rels/.rels"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/package\" Target=\"embeddings/oleObject1.bin\"/>"
                    + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>Doc with OLE</w:t></w:r></w:p></w:body>"
                    + "</w:document>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/embeddings/oleObject1.bin"));
            zos.write(oleBytes);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    static byte[] wrapAsOle10Native(String fileName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Write fileName as null-terminated ASCII
        baos.write(fileName.getBytes(StandardCharsets.US_ASCII));
        baos.write(0);
        // Write content
        baos.write(content);
        return baos.toByteArray();
    }

    // ========== Minimal .doc (OLE2-based Word 97-2003) ==========

    public static byte[] createMinimalDoc() throws IOException {
        // Build an OLE2 stream with a WordDocument stream — minimal but parseable by HWPF
        // HWPF needs: WordDocument, 1Table, 0Table, CompObj
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            var root = fs.getRoot();

            // Create a minimal WordDocument stream that HWPF can parse
            byte[] wordDoc = createMinimalWordDocument();
            root.createDocument("WordDocument", new ByteArrayInputStream(wordDoc));

            // Empty table streams — required by HWPF
            root.createDocument("1Table", new ByteArrayInputStream(new byte[0]));
            root.createDocument("0Table", new ByteArrayInputStream(new byte[0]));

            // CompObj is optional but good practice
            byte[] compObj = createCompObj();
            root.createDocument("CompObj", new ByteArrayInputStream(compObj)); // actually \x05

            fs.writeFilesystem(baos);
        }
        return baos.toByteArray();
    }

    private static byte[] createMinimalWordDocument() throws IOException {
        // Fib base + ccpText — minimal FIB to make HWPF not crash
        // This is the binary File Information Block header for Word 97
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // wIdent (magic: 0xA5EC)
        baos.write(new byte[]{(byte) 0xEC, (byte) 0xA5});

        // nFib (version) — use 0x00C1 (Word 97)
        baos.write(new byte[]{(byte) 0xC1, 0x00});

        // Simplified: write enough bytes for HWPF to parse without crashing
        // flags / lid / etc. — pad with zeros
        // We need at least fibRgLw (header) + some ccpText
        // ccpText at offset 0x004C

        byte[] header = new byte[0x0050];
        header[0] = (byte) 0xEC; // wIdent low
        header[1] = (byte) 0xA5; // wIdent high
        header[2] = (byte) 0xC1; // nFib low
        header[3] = 0x00;        // nFib high

        // ccpText at offset 0x004C — 1 character
        header[0x4C] = 1;

        baos.write(header);

        // Character text — "X" — 2 bytes per char in Unicode
        baos.write(new byte[]{'X', 0x00});

        return baos.toByteArray();
    }

    private static byte[] createCompObj() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(new byte[28]); // header (includes CLSID placeholder)
        baos.write("Word.Document.8\0".getBytes(StandardCharsets.US_ASCII));
        return baos.toByteArray();
    }

    // ========== PDF with embedded image ==========

    public static byte[] createPdfWithImage() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        doc.addPage(page);

        // Create a small in-memory PNG image as PDImageXObject
        org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc,
                        new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB));

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, 50, 600, 100, 100);
        }

        return toBytes(doc);
    }

    // ========== PNG magic bytes for MIME detection tests ==========

    private static final byte[] MINIMAL_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR chunk
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // 1x1 pixel
            0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53, (byte) 0xDE, // rest of IHDR
            0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, // IDAT chunk
            0x08, (byte) 0xD7, 0x63, 0x60, 0x60, 0x60, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01, // compressed data
            0x27, 0x34, 0x0A, (byte) 0x9E, // CRC
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82 // IEND
    };

    public static byte[] createMinimalPng() {
        return MINIMAL_PNG.clone();
    }

    // ========== Utility ==========

    private static String buildContentTypesXml(EmbeddedEntry[] entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
          .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
          .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        for (EmbeddedEntry e : entries) {
            String ext = e.fileName.substring(e.fileName.lastIndexOf('.') + 1);
            sb.append("<Default Extension=\"").append(ext).append("\" ContentType=\"").append(e.contentType).append("\"/>");
        }
        sb.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>");
        sb.append("</Types>");
        return sb.toString();
    }

    private static String buildTopRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
    }

    private static String buildRelationsXml(EmbeddedEntry[] entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
          .append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>");
        int i = 2;
        for (EmbeddedEntry e : entries) {
            sb.append("<Relationship Id=\"rId").append(i)
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/package\"")
              .append(" Target=\"embeddings/").append(e.fileName).append("\"/>");
            i++;
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private static String buildDocumentXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>"
                + "<w:p><w:r><w:t>Main document text</w:t></w:r></w:p>"
                + "</w:body>"
                + "</w:document>";
    }

    private static byte[] toBytes(XWPFDocument doc) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { doc.write(baos); return baos.toByteArray(); }
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { wb.write(baos); return baos.toByteArray(); }
    }

    private static byte[] toBytes(PDDocument doc) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { doc.save(baos); return baos.toByteArray(); }
    }

    public static InputStream toInputStream(byte[] data) { return new ByteArrayInputStream(data); }
}
