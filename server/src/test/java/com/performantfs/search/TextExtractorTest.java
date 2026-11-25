package com.performantfs.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.search.TextExtractor.Limits;
import com.performantfs.search.TextExtractor.Result;
import com.performantfs.support.Pdfs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor(Limits.DEFAULT);

    private static Result extract(TextExtractor extractor, byte[] bytes) throws IOException {
        return extractor.extract(new ByteArrayInputStream(bytes), bytes.length);
    }

    private Result extract(byte[] bytes) throws IOException {
        return extract(extractor, bytes);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsUtf8TextAndCollapsesWhitespace() throws IOException {
        assertThat(extract(utf8("﻿# Notes\n\n  Quarterly\treview\r\n- café\n")))
                .isEqualTo(new Result.Text("# Notes Quarterly review - café"));
    }

    @Test
    void decidesByContentNotName() throws IOException {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};
        assertThat(extract(png)).isEqualTo(new Result.Unsupported());
        assertThat(extract(utf8("const answer = 42;"))).isInstanceOf(Result.Text.class);
    }

    @Test
    void rejectsBytesThatAreNotUtf8() throws IOException {
        byte[] latin1 = "café crème".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(extract(latin1)).isEqualTo(new Result.Unsupported());
        assertThat(extract(new byte[0])).isEqualTo(new Result.Unsupported());
    }

    @Test
    void acceptsACharacterCutInHalfByTheSample() {
        // "é" is two bytes; put its first byte last in the 8 KB sample.
        byte[] head = Arrays.copyOf(utf8("a".repeat(8191) + "é"), 8192);
        assertThat(TextExtractor.isUtf8Text(head)).isTrue();
    }

    @Test
    void keepsAtMostMaxChars() throws IOException {
        var small = new TextExtractor(new Limits(10, 1 << 20, 10, Duration.ofSeconds(5)));
        assertThat(extract(small, utf8("abcdefghijklmnop"))).isEqualTo(new Result.Text("abcdefghij"));
        // A character outside the BMP is two chars in Java; never keep half of one.
        assertThat(extract(small, utf8("abcdefghi😀z"))).isEqualTo(new Result.Text("abcdefghi"));
    }

    @Test
    void readsTextFromEveryPageOfAPdf() throws IOException {
        Result result = extract(Pdfs.withPages("Homework five: the halting problem", "Page two mentions entropy"));
        assertThat(result).isInstanceOf(Result.Text.class);
        assertThat(((Result.Text) result).text()).contains("halting problem").contains("entropy");
    }

    @Test
    void stopsReadingAPdfAtMaxChars() throws IOException {
        var small = new TextExtractor(new Limits(12, 1 << 20, 10, Duration.ofSeconds(5)));
        Result result = extract(small, Pdfs.withPages("first page text", "second page text"));
        assertThat(result).isInstanceOf(Result.Text.class);
        assertThat(((Result.Text) result).text()).hasSizeLessThanOrEqualTo(12).startsWith("first page");
    }

    @Test
    void readsOnlyTheFirstPagesOfALongPdf() throws IOException {
        var twoPages = new TextExtractor(new Limits(1000, 1 << 20, 2, Duration.ofSeconds(5)));
        Result result = extract(twoPages, Pdfs.withPages("alpha", "bravo", "charlie"));
        assertThat(((Result.Text) result).text()).contains("alpha").contains("bravo").doesNotContain("charlie");
    }

    @Test
    void givesUpOnPdfsItCannotRead() throws IOException {
        assertThat(extract(Pdfs.encrypted("secret", "hidden words"))).isEqualTo(new Result.Failed("encrypted"));
        assertThat(extract(utf8("%PDF-1.7\nthis is not really a pdf"))).isEqualTo(new Result.Failed("unreadable"));
        var tiny = new TextExtractor(new Limits(1000, 100, 10, Duration.ofSeconds(5)));
        assertThat(extract(tiny, Pdfs.withPages("big"))).isEqualTo(new Result.Failed("too large"));
    }

    @Test
    void givesUpOnAPdfThatRunsOutOfTime() throws IOException {
        var hurried = new TextExtractor(new Limits(1000, 1 << 20, 10, Duration.ZERO));
        assertThat(extract(hurried, Pdfs.withPages("slow"))).isEqualTo(new Result.Failed("too slow"));
    }
}
