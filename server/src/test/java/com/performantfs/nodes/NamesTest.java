package com.performantfs.nodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.performantfs.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NamesTest {

    @ParameterizedTest
    @CsvSource({
        "report.pdf, report (2).pdf",
        "archive.tar.gz, archive (2).tar.gz",
        ".env, .env (2)",
        "Makefile, Makefile (2)",
        "my.photo.2024.jpg, my.photo.2024 (2).jpg",
    })
    void numberedNamesKeepTheExtension(String name, String expected) {
        assertThat(Names.numbered(name, 2)).isEqualTo(expected);
    }

    @Test
    void normalisesToNfcSoComposedAndDecomposedAreOneName() {
        assertThat(Names.normalize("café")).isEqualTo("café");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(Names.normalize("  notes.txt ")).isEqualTo("notes.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", ".", "..", "a/b"})
    void rejectsInvalidNames(String name) {
        assertThatThrownBy(() -> Names.normalize(name)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsNamesOverTheLimit() {
        assertThatThrownBy(() -> Names.normalize("x".repeat(256))).isInstanceOf(ApiException.class);
        assertThat(Names.normalize("x".repeat(255))).hasSize(255);
    }

    @Test
    void splitsPathsAndValidatesEachSegment() {
        assertThat(Names.splitPath("/a//b/")).containsExactly("a", "b");
        assertThatThrownBy(() -> Names.splitPath("a/../b")).isInstanceOf(ApiException.class);
    }
}
