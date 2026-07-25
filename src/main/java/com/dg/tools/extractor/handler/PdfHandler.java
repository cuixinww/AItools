package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * PDF 处理器（PdfHandler）。
 *
 * 基于 Apache PDFBox 解析 PDF 文档：
 *   - 文本：使用 PDFTextStripper 抽取，按空行把连续行合并为段落；
 *   - 图片：遍历每页 PDResources 中的 XObject，把图片解码后作为原始字节抽取。
 *
 * 对应 SPEC §9 路由表：.pdf → PdfHandler。
 */
@Component
@Slf4j
public class PdfHandler extends AbstractHandler {

    public PdfHandler() {
        super();
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    // ==================== 阶段 1：UNPACK（拆包） ====================

    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("pdf", fileName);
        int pos = 0;

        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            for (PDPage page : doc.getPages()) {
                pos += extractImagesFromPage(page.getResources(), pos,
                        unpacked::addImage, err -> unpacked.addError("unpack images: " + err));
            }
        } catch (Exception e) {
            log.error("Failed to unpack pdf: {}", fileName, e);
            unpacked.addError("unpack error: " + e.getMessage());
        }

        return unpacked;
    }

    // ==================== 阶段 2：EXTRACT（解析） ====================

    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("pdf", fileName);
        int position = 0;

        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            String[] rawLines = text.split("\\r?\\n");

            StringBuilder paraBuf = new StringBuilder();
            for (String line : rawLines) {
                if (line.isBlank()) {
                    if (!paraBuf.isEmpty()) {
                        result.addElement(new Element(position++, "paragraph", paraBuf.toString().trim()));
                        paraBuf.setLength(0);
                    }
                } else {
                    if (!paraBuf.isEmpty()) paraBuf.append(' ');
                    paraBuf.append(line.trim());
                }
            }
            if (!paraBuf.isEmpty()) {
                result.addElement(new Element(position++, "paragraph", paraBuf.toString().trim()));
            }

            for (PDPage page : doc.getPages()) {
                position += extractImagesFromPage(page.getResources(), position,
                        result::addImage, err -> result.addError("extract images: " + err));
            }

        } catch (Exception e) {
            log.error("Failed to parse pdf: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    // ==================== 共享的图片抽取 ====================

    private int extractImagesFromPage(PDResources resources, int startPos,
                                       Consumer<ImageFile> imageConsumer, Consumer<String> errorConsumer) {
        if (resources == null) return 0;
        int pos = startPos;
        try {
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (!(xobj instanceof PDImageXObject img)) continue;

                ImageBytes ib = readImageBytes(img);
                if (ib.bytes.length == 0) continue;
                String imgName = "pdf_image_" + pos + "." + ib.format;
                imageConsumer.accept(new ImageFile(imgName, pos, ib.bytes, ib.format));
                pos++;
            }
        } catch (Exception e) {
            errorConsumer.accept(e.getMessage());
        }
        return pos - startPos;
    }

    // ==================== 图片解码 ====================

    private static ImageBytes readImageBytes(PDImageXObject img) {
        try {
            String suffix = img.getSuffix();
            if ("jpg".equalsIgnoreCase(suffix) || "jpeg".equalsIgnoreCase(suffix)) {
                try (InputStream raw = img.getCOSObject().createRawInputStream()) {
                    return new ImageBytes(raw.readAllBytes(), "jpg");
                }
            }
            BufferedImage bi = img.getImage();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            return new ImageBytes(baos.toByteArray(), "png");
        } catch (Exception e) {
            try (InputStream raw = img.getCOSObject().createRawInputStream()) {
                String fmt = img.getSuffix();
                return new ImageBytes(raw.readAllBytes(), fmt != null ? fmt : "png");
            } catch (Exception ex) {
                log.error("Failed to read PDF image", ex);
                return new ImageBytes(new byte[0], "png");
            }
        }
    }

    private static class ImageBytes {
        final byte[] bytes;
        final String format;
        ImageBytes(byte[] bytes, String format) { this.bytes = bytes; this.format = format; }
    }
}
