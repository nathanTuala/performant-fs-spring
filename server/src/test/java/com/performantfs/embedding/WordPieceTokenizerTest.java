package com.performantfs.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checked against Hugging Face's BertWordPieceTokenizer: reference.json holds the ids it gives
 * for awkward inputs (accents, CJK, emoji, punctuation, an overlong word).
 */
class WordPieceTokenizerTest {

    static final Path MODEL = Path.of("models/bge-small-en-v1.5");
    private static WordPieceTokenizer tokenizer;
    private static JsonNode reference;

    @BeforeAll
    static void load() throws IOException {
        tokenizer = WordPieceTokenizer.load(MODEL.resolve("vocab.txt"));
        try (InputStream in = WordPieceTokenizerTest.class.getResourceAsStream("/embedding/reference.json")) {
            reference = JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void matchesHuggingFaceTokenForToken() {
        for (JsonNode c : reference.path("tokens")) {
            long[] expected = c.path("ids").valueStream().mapToLong(JsonNode::asLong).toArray();
            assertThat(tokenizer.encode(c.path("text").asString(), 512)).as(c.path("text").asString()).containsExactly(expected);
        }
    }

    @Test
    void splitsWordsIntoVocabularyPieces() {
        assertThat(WordPieceTokenizer.basicTokens("Don't, Café!")).containsExactly("don", "'", "t", ",", "cafe", "!");
    }

    @Test
    void truncatesButAlwaysEndsWithSep() {
        long[] ids = tokenizer.encode("word ".repeat(1000), 16);
        assertThat(ids).hasSize(16);
        assertThat(ids[0]).isEqualTo(101);
        assertThat(ids[15]).isEqualTo(102);
        assertThat(Arrays.stream(ids, 1, 15).distinct().count()).isEqualTo(1);
    }
}
