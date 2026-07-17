package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;

import java.io.InputStream;
import java.util.Set;

public class ImageHandler implements DocumentHandler {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tiff", "tif", "webp"
    );

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }

        ExtractionResult result = new ExtractionResult("image", fileName);

        try {
            byte[] data = is.readAllBytes();
            String format = extractFormat(fileName);

            ImageFile img = new ImageFile(fileName, 0, data, format);
            result.addImage(img);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read image: " + fileName, e);
        }

        return result;
    }

    private String extractFormat(String fileName) {
        if (fileName == null) return "png";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "png";
        String ext = fileName.substring(dot + 1).toLowerCase();
        if ("jpeg".equals(ext)) return "jpg";
        if ("tif".equals(ext)) return "tiff";
        return ext;
    }
}
