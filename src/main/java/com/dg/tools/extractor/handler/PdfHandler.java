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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * PDF 处理器（.pdf）。
 *
 * 基于 Apache PDFBox 解析 PDF 文档，核心特性：
 * <ul>
 *   <li>图文混排排序：使用自定义 YCoordinateStripper 按 Y 坐标收集文本位置，
 *          与图片的 Y 坐标混合排序，保持图文相对顺序</li>
 *   <li>图片提取：遍历每页 PDResources 中的 XObject，记录文件名、字节和格式</li>
 *   <li>JPEG 优化：JPEG 图片直接使用原始字节流（无需重新编码），其余格式转 PNG</li>
 * </ul>
 *
 * <p><b>内存注意</b>：PDFBox 的 Loader.loadPDF() 需要 byte[] 输入，
 * 因此 doUnpack 和 doExtract 都会一次性读取整个文件到内存。
 * 这是 PDFBox API 的设计限制，暂无法改为流式处理。</p>
 *
 * @see StoreWriter#writeMedia
 */
@Component
@Slf4j
public class PdfHandler extends AbstractHandler {

    public PdfHandler() {
        super();
    }

    /** 判断当前文件名是否为 .pdf 扩展名。 */
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    // ==================== 阶段 1：UNPACK（仅图片） ====================

    /**
     * Phase 1 拆包：仅提取所有页面的图片，不解析文本内容。
     */
    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("pdf", fileName);
        int pos = 0;
        // PDFBox Loader 需要 byte[]，无法流式传入 InputStream
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

    // ==================== 阶段 2：EXTRACT（完整解析） ====================

    /**
     * Phase 2 完整解析：逐页面处理，每页内图文按 Y 坐标混合排序。
     * <p>处理流程：
     * <ol>
     *   <li>用 YCoordinateStripper 收集全文文本跨度及 Y 坐标</li>
     *   <li>逐页处理：收集本頁图片 → 合并文本跨度 → 按 Y 降序排列</li>
     *   <li>顺序遍历 Span：图片直接提交；文本追加到 StringBuilder，遇到空行或图片时 flush 为 paragraph</li>
     * </ol>
     * <p>注意：PDFBox 的 Page#getResources().getXObjectNames() 返回的图片
     * 不包含精确的渲染位置信息，Y 坐标 fallback 到 pageHeight * 0.9f。
     */
    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("pdf", fileName);
        int position = 0;
        // PDFBox Loader 需要 byte[]，无法流式传入 InputStream
        byte[] data = is.readAllBytes();

        try (PDDocument doc = Loader.loadPDF(data)) {
            // 用自定义 stripper 按 Y 坐标收集全文本跨度
            YCoordinateStripper stripper = new YCoordinateStripper();
            stripper.setSortByPosition(true);
            stripper.getText(doc); // 填充内部 pageSpans

            // 逐页处理：按 Y 坐标从上到下合并图片和文本
            for (int pageIdx = 0; pageIdx < doc.getNumberOfPages(); pageIdx++) {
                // 收集本页图片（带 fallback Y 坐标）
                List<ImageSpan> pageImages = collectPageImages(doc.getPage(pageIdx), pageIdx);

                // 收集本页文本（已带 Y 坐标）
                List<TextSpan> pageText = stripper.getTextSpansForPage(pageIdx);

                // 合并所有跨度
                List<Span> allSpans = new ArrayList<>();
                for (ImageSpan img : pageImages) {
                    allSpans.add(new Span(img.y, true, img));
                }
                for (TextSpan ts : pageText) {
                    allSpans.add(new Span(ts.y, false, ts));
                }
                // 按 Y 坐标降序排列（PDF 坐标系中 Y 越大越靠近页面顶部）
                allSpans.sort((a, b) -> Float.compare(b.y, a.y));

                // 顺序遍历生成 Element
                StringBuilder paraBuf = new StringBuilder();
                for (Span span : allSpans) {
                    if (span.isImage) {
                        // 图片 → 先 flush 缓冲文本，再提交图片
                        if (!paraBuf.isEmpty()) {
                            result.addElement(new Element(position++, "paragraph",
                                    paraBuf.toString().trim()));
                            paraBuf.setLength(0);
                        }
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
                            // 空行 → 段落分隔（flush 已有文本）
                            if (!paraBuf.isEmpty()) {
                                result.addElement(new Element(position++, "paragraph",
                                        paraBuf.toString().trim()));
                                paraBuf.setLength(0);
                            }
                        } else {
                            // 非空文本 → 追加到缓冲区（前后文本间加空格）
                            if (!paraBuf.isEmpty()) paraBuf.append(' ');
                            paraBuf.append(ts.text.trim());
                        }
                    }
                }
                // 提交最后一页末尾剩余的文本
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

    /**
     * 收集指定页面的所有图片（从 PDResources 的 XObject 中查找）。
     * Y 坐标 fallback 到 pageHeight * 0.9f（PDF XObject 不含位置信息，
     * 精确位置需解析 ContentStream 指令，此处不做）。
     */
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

                // Y 坐标 fallback：PDF XObject 本身不含位置信息，
                // 精确位置需解析 ContentStream 的指令（Tm/CTM），暂不实现
                float y = pageHeight * 0.9f;
                String imgName = "pdf_image_" + pageIdx + "_" + result.size() + "." + ib.format;
                result.add(new ImageSpan(imgName, ib.bytes, ib.format, y));
            }
        } catch (Exception e) {
            log.debug("Failed to collect page images: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 共享的图片抽取方法，供 Phase 1 unpack 使用。
     * 通过 Consumer 回调交付结果，避免重复代码。
     */
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

    /**
     * 读取 PDImageXObject 的字节数据。
     * JPEG 图片直接使用原始流（避免 JPEG-in-PNG 再编码导致的画质损失）。
     * 其余格式先解码为 BufferedImage 再转 PNG。
     */
    private static ImageBytes readImageBytes(PDImageXObject img) {
        try {
            String suffix = img.getSuffix();
            // JPEG 图片：直接使用原始流，无需重新编码
            if ("jpg".equalsIgnoreCase(suffix) || "jpeg".equalsIgnoreCase(suffix)) {
                try (InputStream raw = img.getCOSObject().createRawInputStream()) {
                    return new ImageBytes(raw.readAllBytes(), "jpg");
                }
            }
            // 其余格式：先解码为 BufferedImage，统一转为 PNG
            BufferedImage bi = img.getImage();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            return new ImageBytes(baos.toByteArray(), "png");
        } catch (Exception e) {
            // 最终回退：尝试读取原始流
            try (InputStream raw = img.getCOSObject().createRawInputStream()) {
                String fmt = img.getSuffix();
                return new ImageBytes(raw.readAllBytes(), fmt != null ? fmt : "png");
            } catch (Exception ex) {
                log.error("Failed to read PDF image", ex);
                return new ImageBytes(new byte[0], "png");
            }
        }
    }

    // ==================== 内部类型 ====================

    /** 文本跨度包装器：自定义 PDFTextStripper，按页收集文本及其 Y 坐标。 */
    private static class YCoordinateStripper extends PDFTextStripper {
        // pageIdx → list of TextSpan
        private final List<List<TextSpan>> pageSpans = new ArrayList<>();
        private int currentPage = -1;
        private List<TextSpan> currentSpans;

        YCoordinateStripper() throws java.io.IOException {
            super();
        }

        /** 每个新页面开始时初始化对应的 TextSpan 列表。 */
        @Override
        protected void startPage(PDPage page) throws java.io.IOException {
            super.startPage(page);
            currentPage++;
            while (pageSpans.size() <= currentPage) {
                pageSpans.add(new ArrayList<>());
            }
            currentSpans = pageSpans.get(currentPage);
        }

        /** PDFBox 回调：每次写入一段文本时调用。记录首 TextPosition 的 Y 坐标作为跨度坐标。 */
        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (textPositions.isEmpty()) return;
            // 取第一个字符的 Y 坐标代表整段文本的位置
            TextPosition first = textPositions.get(0);
            float y = first.getYDirAdj();
            currentSpans.add(new TextSpan(text, y));
        }

        /** 按页索引获取文本跨度列表。 */
        List<TextSpan> getTextSpansForPage(int pageIdx) {
            if (pageIdx < 0 || pageIdx >= pageSpans.size()) return Collections.emptyList();
            return pageSpans.get(pageIdx);
        }
    }

    /** 图片字节 + 格式的轻量包装。 */
    private static class ImageBytes {
        final byte[] bytes;
        final String format;
        ImageBytes(byte[] bytes, String format) { this.bytes = bytes; this.format = format; }
    }

    /** 文本跨度：包含文本内容和 Y 坐标。 */
    private static class TextSpan {
        final String text;
        final float y;
        TextSpan(String text, float y) { this.text = text; this.y = y; }
    }

    /** 图片跨度：包含文件名、字节、格式和 Y 坐标。 */
    private static class ImageSpan {
        final String fileName;
        final byte[] bytes;
        final String format;
        final float y;
        ImageSpan(String fn, byte[] b, String fmt, float y) {
            this.fileName = fn; this.bytes = b; this.format = fmt; this.y = y;
        }
    }

    /** 统一的跨度包装：区分图片/文本，按 Y 坐标排序后合并。 */
    private static class Span {
        final float y;
        final boolean isImage;
        final Object payload;
        Span(float y, boolean isImage, Object payload) {
            this.y = y; this.isImage = isImage; this.payload = payload;
        }
    }
}
