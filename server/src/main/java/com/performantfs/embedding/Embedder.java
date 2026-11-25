package com.performantfs.embedding;

import java.util.List;

/** Turns text into vectors that sit close together when the texts mean similar things. */
public interface Embedder {

    /** Length of every vector; matches the vector(384) columns. */
    int DIMENSIONS = 384;

    /** One unit-length vector per text, in order. Long texts are cut to the model's window. */
    float[][] embed(List<String> texts);

    default float[] embed(String text) {
        return embed(List.of(text))[0];
    }
}
