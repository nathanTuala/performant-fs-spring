package com.performantfs.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PassagesTest {

    private static String words(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
    }

    @Test
    void keepsShortTextWhole() {
        assertThat(new Passages(10, 2, 5).split("  just a few words ")).containsExactly("just a few words");
        assertThat(new Passages(10, 2, 5).split("   ")).isEmpty();
    }

    @Test
    void overlapsNeighbouringPassages() {
        assertThat(new Passages(4, 1, 10).split(words(0, 10)))
                .containsExactly("w0 w1 w2 w3", "w3 w4 w5 w6", "w6 w7 w8 w9");
    }

    @Test
    void neverEmitsAPassageThatOnlyRepeatsTheOverlap() {
        assertThat(new Passages(4, 1, 10).split(words(0, 7))).containsExactly("w0 w1 w2 w3", "w3 w4 w5 w6");
    }

    @Test
    void stopsAtMax() {
        assertThat(new Passages(4, 0, 2).split(words(0, 100))).containsExactly("w0 w1 w2 w3", "w4 w5 w6 w7");
    }

    @Test
    void rejectsAnOverlapThatWouldNeverAdvance() {
        assertThatThrownBy(() -> new Passages(4, 4, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
