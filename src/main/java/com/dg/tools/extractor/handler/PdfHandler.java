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
import org.apache.pdfbox.text.TextPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;

/**
 * PDF 处理器（PdfHandler）。
 *
 * 基于 Apache PDFBox 解析 PDF 文档：
 *   - 文本：使用自定义 PDFTextStripper 按 Y 坐标收集文本位置，
 *          将图像与其附近的文本按 Y 坐标混合排序；
 *   - 图片：遍历每页 PDResources 中的 XObject，记录其 Y 坐标，
 *          与文本跨度合并后生成有序的 Element 序列。
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
        byte[] data = is.readAllBytes();

        try (PDDocument doc = Loader.loadPDF(data)) {
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
        byte[] data = is.readAllBytes();

        try (PDDocument doc = Loader.loadPDF(data)) {
            // 用自定义 stripper 按 Y 坐标收集文本跨度
            YCoordinateStripper stripper = new YCoordinateStripper();
            stripper.setSortByPosition(true);
            stripper.getText(doc); // 填充 textSpans

            // 按 Y 坐标从上到下处理 PDF 内容
            for (int pageIdx = 0; pageIdx < doc.getNumberOfPages(); pageIdx++) {
                // 收集本页图片（带 Y 坐标）
                List<ImageSpan> pageImages = collectPageImages(doc.getPage(pageIdx), pageIdx);

                // 收集本页文本（带 Y 坐标）
                List<TextSpan> pageText = stripper.getTextSpansForPage(pageIdx);

                // 合并排序
                List<Span> allSpans = new ArrayList<>();
                for (ImageSpan img : pageImages) {
                    allSpans.add(new Span(img.y, true, img));
                }
                for (TextSpan ts : pageText) {
                    allSpans.add(new Span(ts.y, false, ts));
                }
                // 按 Y 坐标降序排列（PDF 中 Y 越大越靠近页面顶部）
                allSpans.sort((a, b) -> Float.compare(b.y, a.y));

                // 生成 Element
                StringBuilder paraBuf = new StringBuilder();
                for (Span span : allSpans) {
                    if (span.isImage) {
                        // 先将缓冲的文本提交为段落
                        if (!paraBuf.isEmpty()) {
                            result.addElement(new Element(position++, "paragraph",
                                    paraBuf.toString().trim()));
                            paraBuf.setLength(0);
                        }
                        // 提交图片
                        ImageSpan imgSpan = (ImageSpan) span.payload;
                        String imgName = imgSpan.fileName;
                        result.addImage(new ImageFile(imgName, position, imgSpan.bytes, imgSpan.format));
                        result.addElement(new Element(position, "image", null,
                                "file: media/" + imgName));
                        position++;
                    } else {
                        // 文本跨度
                        TextSpan ts = (TextSpan) span.payload;
                        if (ts.text.isBlank()) {
                            // 空行 → 段落分隔
                            if (!paraBuf.isEmpty()) {
                                result.addElement(new Element(position++, "paragraph",
                                        paraBuf.toString().trim()));
                                paraBuf.setLength(0);
                            }
                        } else {
                            if (!paraBuf.isEmpty()) paraBuf.append(' ');
                            paraBuf.append(ts.text.trim());
                        }
                    }
                }
                // 提交最后一段文本
                if (!paraBuf.isEmpty()) {
                    result.addElement(new Element(position++, "paragraph",
                            paraBuf.toString().trim()));
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse pdf: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    // ==================== 图片收集 ====================

    private List<ImageSpan> collectPageImages(PDPage page, int pageIdx) {
        List<ImageSpan> result = new ArrayList<>();
        try {
            PDResources resources = page.getResources();
            if (resources == null) return result;
            float pageHeight = page.getMediaBox().getHeight();
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (!(xobj instanceof PDImageXObject img)) continue;
                ImageBytes ib = readImageBytes(img);
                if (ib.bytes.length == 0) continue;

                // PDF 图片默认 Y 坐标位于页面顶部（最大 Y 接近 pageHeight）。
                // 此 fallback 位置无法精确反映图片在原 PDF 中的实际渲染位置，
                // 因为 XObject 本身不含位置信息；位置信息分散在 ContentStream 的指令中。
                float y = pageHeight * 0.9f;
                String imgName = "pdf_image_" + pageIdx + "_" + result.size() + "." + ib.format;
                ImageSpan is = new ImageSpan(imgName, ib.bytes, ib.format, y);
                result.add(is);
            }
        } catch (Exception e) {
            log.debug("Failed to collect page images: {}", e.getMessage());
        }
        return result;
    }

    // ==================== 共享的图片抽取（Phase 1 unpack 用） ====================

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

    // ==================== Y 坐标文本提取器 ====================

    /**
     * 自定义 PDFTextStripper，收集每页文本跨度及对应的 Y 坐标。
     */
    private static class YCoordinateStripper extends PDFTextStripper {
        // pageIdx → list of TextSpan
        private final List<List<TextSpan>> pageSpans = new ArrayList<>();
        private int currentPage = -1;
        private List<TextSpan> currentSpans;

        YCoordinateStripper() throws java.io.IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws java.io.IOException {
            super.startPage(page);
            currentPage++;
            while (pageSpans.size() <= currentPage) {
                pageSpans.add(new ArrayList<>());
            }
            currentSpans = pageSpans.get(currentPage);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (textPositions.isEmpty()) return;
            TextPosition first = textPositions.get(0);
            float y = first.getYDirAdj();
            TextSpan span = new TextSpan(text, y);
            currentSpans.add(span);
        }

        List<TextSpan> getTextSpansForPage(int pageIdx) {
            if (pageIdx < 0 || pageIdx >= pageSpans.size()) return Collections.emptyList();
            return pageSpans.get(pageIdx);
        }
    }

    // ==================== 内部类型 ====================

    private static class ImageBytes {
        final byte[] bytes;
        final String format;
        ImageBytes(byte[] bytes, String format) { this.bytes = bytes; this.format = format; }
    }

    /** 文本跨度。 */
    private static class TextSpan {
        final String text;
        final float y;
        TextSpan(String text, float y) { this.text = text; this.y = y; }
    }

    /** 图片跨度。 */
    private static class ImageSpan {
        final String fileName;
        final byte[] bytes;
        final String format;
        final float y;
        ImageSpan(String fn, byte[] b, String fmt, float y) {
            this.fileName = fn; this.bytes = b; this.format = fmt; this.y = y;
        }
    }

    /** 统一的跨度包装（文本或图片）。 */
    private static class Span {
        final float y;
        final boolean isImage;
        final Object payload;
        Span(float y, boolean isImage, Object payload) {
            this.y = y; this.isImage = isImage; this.payload = payload;
        }
    }
}
