package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;

public class PdfHandler implements DocumentHandler {

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("pdf", fileName);
        int position = 0;

        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            // 1. Text extraction
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                if (!line.isBlank()) {
                    result.addElement(new Element(position++, "paragraph", line.trim()));
                }
            }

            // 2. Image extraction from each page
            for (PDPage page : doc.getPages()) {
                extractImagesFromResources(page.getResources(), result, position);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse pdf: " + fileName, e);
        }

        return result;
    }

    private void extractImagesFromResources(PDResources resources, ExtractionResult result, int startPos) {
        if (resources == null) return;
        int pos = startPos + result.getImages().size();
        try {
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    String format = detectImageFormat(img.getSuffix());
                    String imgName = "pdf_image_" + pos + "." + format;
                    COSStream cosStream = (COSStream) img.getCOSObject();
                    byte[] imgBytes;
                    try (InputStream imgIn = cosStream.createRawInputStream()) {
                        imgBytes = imgIn.readAllBytes();
                    }
                    result.addImage(new ImageFile(imgName, pos, imgBytes, format));
                    pos++;
                }
            }
        } catch (Exception e) {
            // silently skip image extraction failures
        }
    }

    private String detectImageFormat(String suffix) {
        if (suffix == null) return "png";
        return switch (suffix.toLowerCase()) {
            case "jpg", "jpeg" -> "jpg";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            case "tiff", "tif" -> "tiff";
            default -> "png";
        };
    }
}
