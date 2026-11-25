package com.performantfs.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OnnxEmbedderTest {

    private static OnnxEmbedder embedder;

    @BeforeAll
    static void load() throws Exception {
        embedder = new OnnxEmbedder(Path.of("models/bge-small-en-v1.5"));
    }

    @AfterAll
    static void close() throws Exception {
        embedder.close();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot; // both are unit length
    }

    @Test
    void matchesTheReferenceImplementation() throws Exception {
        JsonNode reference;
        try (InputStream in = getClass().getResourceAsStream("/embedding/reference.json")) {
            reference = JsonMapper.builder().build().readTree(in);
        }
        // Embedded together, so padding is exercised: the second sentence is shorter.
        List<String> texts = reference.path("embeddings").valueStream().map(e -> e.path("text").asString()).toList();
        float[][] vectors = embedder.embedPassages(texts);
        for (int i = 0; i < texts.size(); i++) {
            JsonNode expected = reference.path("embeddings").get(i).path("first8");
            for (int d = 0; d < 8; d++) {
                assertThat(vectors[i][d]).as(texts.get(i) + " [" + d + "]").isCloseTo(expected.get(d).floatValue(), within(1e-4f));
            }
        }
    }

    @Test
    void prefixesQueriesWithTheRetrievalInstruction() throws Exception {
        JsonNode reference;
        try (InputStream in = getClass().getResourceAsStream("/embedding/reference.json")) {
            reference = JsonMapper.builder().build().readTree(in);
        }
        for (JsonNode q : reference.path("queries")) {
            float[] v = embedder.embedQuery(q.path("text").asString());
            for (int d = 0; d < 8; d++) {
                assertThat(v[d]).as(q.path("text").asString() + " [" + d + "]").isCloseTo(q.path("first8").get(d).floatValue(), within(1e-4f));
            }
        }
    }

    @Test
    void returnsUnitVectorsOfTheRightLength() {
        float[] v = embedder.embedQuery("quarterly budget review");
        assertThat(v).hasSize(Embedder.DIMENSIONS);
        assertThat(cosine(v, v)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void placesTextsWithTheSameMeaningCloseTogether() {
        float[][] v = embedder.embedPassages(List.of(
                "How do I bake bread at home?",
                "A simple recipe for a sourdough loaf",
                "The deadline for filing your tax return"));
        assertThat(cosine(v[0], v[1])).isGreaterThan(cosine(v[0], v[2]) + 0.2);
    }

    @Test
    void handlesMoreTextsThanOneBatchAndOverlongText() {
        List<String> many = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "note number " + i).toList();
        float[][] vectors = embedder.embedPassages(many);
        assertThat(vectors).hasNumberOfRows(40);
        for (float[] v : vectors) assertThat(v).hasSize(Embedder.DIMENSIONS);
        assertThat(embedder.embedQuery("word ".repeat(5000))).hasSize(Embedder.DIMENSIONS);
    }
}
