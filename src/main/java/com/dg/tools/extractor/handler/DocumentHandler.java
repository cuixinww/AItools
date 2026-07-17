package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;

import java.io.InputStream;

public interface DocumentHandler {
    boolean supports(String fileName);
    ExtractionResult extract(InputStream is, String fileName);
}
