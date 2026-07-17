package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.EmbeddedFile;
import com.dg.tools.extractor.model.ExtractionResult;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ZipHandler implements DocumentHandler {

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".zip");
    }

    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("zip", fileName);

        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            int position = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);

                result.addEmbedded(new EmbeddedFile(entryName, position++, baos.toByteArray()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse zip: " + fileName, e);
        }

        return result;
    }
}
