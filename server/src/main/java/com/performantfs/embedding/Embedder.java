package com.performantfs.embedding;

import java.util.List;

/**
 * Turns text into vectors that sit close together when the texts mean similar things. Queries
 * and passages are embedded separately because retrieval models may treat them differently
 * (a short question vs. a paragraph that answers it).
 */
public interface Embedder {

    /** Length of every vector; matches the vector(384) columns. */
    int DIMENSIONS = 384;

    /** One unit-length vector per passage of a file, in order. Long passages are cut to the model's window. */
    float[][] embedPassages(List<String> passages);

    /** A unit-length vector for a search query, comparable with passage vectors by cosine distance. */
    float[] embedQuery(String query);
}
