package com.dg.tools.extractor.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StringUtilsTest {

    @Test
    void readBytesNormal() throws Exception {
        byte[] input = "hello world".getBytes();
        byte[] result = StringUtils.readBytes(new ByteArrayInputStream(input));
        assertThat(result).isEqualTo(input);
    }

    @Test
    void readBytesEmpty() throws Exception {
        byte[] result = StringUtils.readBytes(new ByteArrayInputStream(new byte[0]));
        assertThat(result).isEmpty();
    }

    @Test
    void sanitizeFileNameNull() {
        assertThat(StringUtils.sanitizeFileName(null)).isEqualTo("unnamed");
    }

    @Test
    void sanitizeFileNameEmpty() {
        assertThat(StringUtils.sanitizeFileName("")).isEqualTo("unnamed");
    }

    @Test
    void sanitizeFileNameNormal() {
        assertThat(StringUtils.sanitizeFileName("hello.txt")).isEqualTo("hello.txt");
    }

    @Test
    void sanitizeFileNameSpecialChars() {
        String result = StringUtils.sanitizeFileName("a/b:c*d?e<f>g|h");
        assertThat(result).doesNotContain("/", ":", "*", "?", "<", ">", "|");
    }

    @Test
    void sanitizeFileNameMultipleUnderscores() {
        assertThat(StringUtils.sanitizeFileName("a___b")).isEqualTo("a_b");
    }

    @Test
    void sanitizeFileNameChinese() {
        assertThat(StringUtils.sanitizeFileName("文件")).isEqualTo("文件");
    }

    @Test
    void escapeCsvNull() {
        assertThat(StringUtils.escapeCsv(null)).isEmpty();
    }

    @Test
    void escapeCsvPlain() {
        assertThat(StringUtils.escapeCsv("hello")).isEqualTo("hello");
    }

    @Test
    void escapeCsvWithComma() {
        assertThat(StringUtils.escapeCsv("a,b")).isEqualTo("\"a,b\"");
    }

    @Test
    void escapeCsvWithQuote() {
        assertThat(StringUtils.escapeCsv("a\"b")).isEqualTo("\"a\"\"b\"");
    }

    @Test
    void escapeCsvWithNewline() {
        assertThat(StringUtils.escapeCsv("a\nb")).isEqualTo("\"a\nb\"");
    }

    @Test
    void escapeCsvWithCarriageReturn() {
        assertThat(StringUtils.escapeCsv("a\rb")).isEqualTo("\"a\rb\"");
    }

    @Test
    void toCsvLineNormal() {
        String line = StringUtils.toCsvLine(List.of("a", "b", "c"));
        assertThat(line).isEqualTo("a,b,c");
    }

    @Test
    void toCsvLineWithEscaping() {
        String line = StringUtils.toCsvLine(List.of("a,b", "c\"d"));
        assertThat(line).isEqualTo("\"a,b\",\"c\"\"d\"");
    }

    @Test
    void toCsvLineEmptyRow() {
        String line = StringUtils.toCsvLine(Arrays.asList("", null, "x"));
        assertThat(line).isEqualTo(",," + "x");
    }
}
