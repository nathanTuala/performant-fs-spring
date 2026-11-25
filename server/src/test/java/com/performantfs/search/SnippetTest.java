package com.performantfs.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.performantfs.search.Snippet.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnippetTest {

    private static String mark(String word) {
        return Snippet.START + word + Snippet.STOP;
    }

    @Test
    void turnsMarksIntoRanges() {
        Snippet s = Snippet.parse("prove the " + mark("halting") + " " + mark("problem") + " is undecidable", false, false);
        assertThat(s.text()).isEqualTo("prove the halting problem is undecidable");
        assertThat(s.highlights()).containsExactly(new Span(10, 17), new Span(18, 25));
        assertThat(s.text().substring(10, 17)).isEqualTo("halting");
    }

    @Test
    void addsEllipsesWhereTheTextWasCut() {
        Snippet s = Snippet.parse("  the " + mark("halting") + " problem ", true, true);
        assertThat(s.text()).isEqualTo("…the halting problem…");
        assertThat(s.highlights()).containsExactly(new Span(5, 12));
    }

    @Test
    void ignoresStrayOrEmptyMarks() {
        Snippet s = Snippet.parse("a" + Snippet.STOP + " b " + Snippet.START + Snippet.STOP + "c", false, false);
        assertThat(s.text()).isEqualTo("a b c");
        assertThat(s.highlights()).isEqualTo(List.of());
    }
}
