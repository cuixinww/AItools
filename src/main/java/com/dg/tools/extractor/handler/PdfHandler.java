package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import com.dg.tools.extractor.model.UnpackResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * PDF 处理器（PdfHandler）。
 *
 * 基于 Apache PDFBox 解析 PDF 文档：
 *   - 文本：使用 PDFTextStripper 抽取，按空行把连续行合并为段落；
 *   - 图片：遍历每页 PDResources 中的 XObject，把图片解码后作为原始字节抽取。
 *
 * 注意：当前实现先输出全部文本段落，再追加全部图片（未在 Y 坐标层面做图文混排）。
 * 若后续需严格按页面位置排序，可接入 PDFStreamEngine 监听绘制指令并按 Y 坐标归并。
 */
public class PdfHandler implements DocumentHandler {

    /**
     * 是否支持该文件：匹配 .pdf。
     */
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    // ==================== 阶段 1：UNPACK（拆包） ====================

    /**
     * 阶段 1 拆包：仅抽取每一页的图片（不含文本）。
     */
    @Override
    public UnpackResult unpack(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        UnpackResult unpacked = new UnpackResult("pdf", fileName);
        int pos = 0;

        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            for (PDPage page : doc.getPages()) {
                pos += extractImagesFromPage(page.getResources(), unpacked, pos);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to unpack pdf: " + fileName, e);
        }

        return unpacked;
    }

    // ==================== 阶段 2：EXTRACT（解析） ====================

    /**
     * 阶段 2 解析：先抽文本（按空行合并段落），再逐页抽图片。
     */
    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("pdf", fileName);
        int position = 0;

        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            // 1. 文本抽取 —— 把连续行在空行处合并为段落
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            String[] rawLines = text.split("\\r?\\n");

            StringBuilder paraBuf = new StringBuilder();
            for (String line : rawLines) {
                if (line.isBlank()) {
                    // 遇到空行：把已累积的行作为一个段落输出
                    if (!paraBuf.isEmpty()) {
                        result.addElement(new Element(position++, "paragraph", paraBuf.toString().trim()));
                        paraBuf.setLength(0);
                    }
                } else {
                    // 非空行：用空格连接，保持同一段落
                    if (!paraBuf.isEmpty()) paraBuf.append(' ');
                    paraBuf.append(line.trim());
                }
            }
            if (!paraBuf.isEmpty()) {
                result.addElement(new Element(position++, "paragraph", paraBuf.toString().trim()));
            }

            // 2. 逐页抽取图片
            for (PDPage page : doc.getPages()) {
                position += extractImagesFromPage(page.getResources(), result, position);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse pdf: " + fileName, e);
        }

        return result;
    }

    // ==================== 共享的图片抽取 ====================

    /**
     * 从 PDResources 中抽取图片，按位置顺序加入结果，返回抽取到的图片数量。
     * 两个重载版本分别服务于 UnpackResult 与 ExtractionResult。
     */
    private int extractImagesFromPage(PDResources resources, UnpackResult result, int startPos) {
        if (resources == null) return 0;
        int pos = startPos;
        try {
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (!(xobj instanceof PDImageXObject img)) continue;

                ImageBytes ib = readImageBytes(img);
                String imgName = "pdf_image_" + pos + "." + ib.format;
                result.addImage(new ImageFile(imgName, pos, ib.bytes, ib.format));
                pos++;
            }
        } catch (Exception e) {
            result.addError("pdf unpack images: " + e.getMessage());
        }
        return pos - startPos;
    }

    private int extractImagesFromPage(PDResources resources, ExtractionResult result, int startPos) {
        if (resources == null) return 0;
        int pos = startPos;
        try {
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (!(xobj instanceof PDImageXObject img)) continue;

                ImageBytes ib = readImageBytes(img);
                String imgName = "pdf_image_" + pos + "." + ib.format;
                result.addImage(new ImageFile(imgName, pos, ib.bytes, ib.format));
                pos++;
            }
        } catch (Exception e) {
            result.addError("pdf extract images: " + e.getMessage());
        }
        return pos - startPos;
    }

    // ==================== 图片解码 ====================

    /**
     * 读取 PDF 图片 XObject 的解码后字节：
     *   - JPEG：DCT 流本身即合法 JPEG 字节，直接透传不做重编码；
     *   - 其它格式（FlateDecode 等）：解码为 BufferedImage 后重新编码为 PNG。
     */
    private static ImageBytes readImageBytes(PDImageXObject img) {
        try {
            String suffix = img.getSuffix();
            // JPEG：原始 DCT 流已是合法 JPEG 字节，无需重编码
            if ("jpg".equalsIgnoreCase(suffix) || "jpeg".equalsIgnoreCase(suffix)) {
                try (InputStream raw = img.getCOSObject().createRawInputStream()) {
                    return new ImageBytes(raw.readAllBytes(), "jpg");
                }
            }
            // 其它格式：经 BufferedImage 解码后编码为 PNG
            BufferedImage bi = img.getImage();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            return new ImageBytes(baos.toByteArray(), "png");
        } catch (Exception e) {
            // 兜底：尝试用原始流返回
            try (InputStream raw = img.getCOSObject().createRawInputStream()) {
                String fmt = img.getSuffix();
                return new ImageBytes(raw.readAllBytes(), fmt != null ? fmt : "png");
            } catch (Exception ex) {
                throw new RuntimeException("Failed to read PDF image", ex);
            }
        }
    }

    /** 图片字节与格式的内部载体。 */
    private static class ImageBytes {
        final byte[] bytes;
        final String format;
        ImageBytes(byte[] bytes, String format) { this.bytes = bytes; this.format = format; }
    }
}
