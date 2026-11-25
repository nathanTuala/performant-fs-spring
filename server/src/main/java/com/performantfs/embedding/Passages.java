package com.performantfs.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits a file's text into overlapping passages for embedding. One vector per passage, rather
 * than one per file, so a long document can match on any part of it, and the matching passage is
 * what the user is shown. Windows overlap so a sentence cut by one boundary is whole in the next.
 *
 * @param words   words per passage: 120 stays inside the model's 256-token window
 * @param overlap words shared by neighbouring passages
 * @param max     passages per file; the rest of a long file is left to keyword search
 */
public record Passages(int words, int overlap, int max) {

    public static final Passages DEFAULT = new Passages(120, 30, 100);

    public Passages {
        if (overlap < 0 || overlap >= words) throw new IllegalArgumentException("overlap must be in [0, words)");
    }

    public List<String> split(String text) {
        String[] all = text.strip().split("\\s+");
        if (all.length == 1 && all[0].isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int step = words - overlap;
        for (int start = 0; start < all.length && out.size() < max; start += step) {
            int end = Math.min(all.length, start + words);
            out.add(String.join(" ", Arrays.copyOfRange(all, start, end)));
            if (end == all.length) break;
        }
        return out;
    }
}
