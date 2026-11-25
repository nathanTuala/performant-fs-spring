package com.performantfs.files;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ByteRangeTest {

    @ParameterizedTest
    @CsvSource({
        "bytes=0-499, 0, 499",
        "bytes=500-, 500, 999",
        "bytes=-500, 500, 999",
        "bytes=-5000, 0, 999",
        "bytes=900-5000, 900, 999",
    })
    void parsesSingleRanges(String header, long start, long end) {
        assertThat(ByteRange.parse(header, 1000)).isEqualTo(new ByteRange.Part(start, end));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=1000-", "bytes=5000-6000", "bytes=-0"})
    void rangesPastTheEndAreUnsatisfiable(String header) {
        assertThat(ByteRange.parse(header, 1000)).isInstanceOf(ByteRange.Unsatisfiable.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "bytes=abc", "items=0-1", "bytes=0-1,5-6", "bytes=500-100", "bytes=-", "bytes=99999999999999999999-"})
    void anythingElseMeansTheWholeFile(String header) {
        assertThat(ByteRange.parse(header, 1000)).isInstanceOf(ByteRange.Full.class);
    }

    @Test
    void anyRangeOnAnEmptyFileIsUnsatisfiable() {
        assertThat(ByteRange.parse("bytes=0-", 0)).isInstanceOf(ByteRange.Unsatisfiable.class);
        assertThat(ByteRange.parse("bytes=-10", 0)).isInstanceOf(ByteRange.Unsatisfiable.class);
    }

    @Test
    void matchesExactWeakListedAndWildcardEtags() {
        assertThat(HttpFields.etagMatches("\"abc\"", "\"abc\"")).isTrue();
        assertThat(HttpFields.etagMatches("W/\"abc\"", "\"abc\"")).isTrue();
        assertThat(HttpFields.etagMatches("\"x\", \"abc\"", "\"abc\"")).isTrue();
        assertThat(HttpFields.etagMatches("*", "\"abc\"")).isTrue();
        assertThat(HttpFields.etagMatches("\"x\"", "\"abc\"")).isFalse();
        assertThat(HttpFields.etagMatches(null, "\"abc\"")).isFalse();
    }

    @Test
    void encodesNonAsciiFilenamesWithAnAsciiFallback() {
        assertThat(HttpFields.contentDisposition(false, "résumé \"v2\".pdf"))
                .isEqualTo("attachment; filename=\"r_sum_ _v2_.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9%20%22v2%22.pdf");
    }
}
