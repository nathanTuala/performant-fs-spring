package com.performantfs.search;

import java.util.ArrayList;
import java.util.List;

/**
 * A short excerpt of a file's text around a match. Highlights are character ranges into
 * {@code text}, not markup: the text came out of an untrusted file, so the client renders it as
 * text and wraps the ranges itself.
 */
public record Snippet(String text, List<Span> highlights) {

    public record Span(int start, int end) {}

    /** Marks Postgres's ts_headline puts around each matched word (never in stored text). */
    static final char START = '\u0002';
    static final char STOP = '\u0003';
    private static final String ELLIPSIS = "…";

    /**
     * Turns ts_headline output into plain text plus ranges.
     *
     * @param cutStart there is text before the excerpt, so it starts with "…"
     * @param cutEnd   there is text after it, so it ends with "…"
     */
    static Snippet parse(String marked, boolean cutStart, boolean cutEnd) {
        var text = new StringBuilder(cutStart ? ELLIPSIS : "");
        List<Span> spans = new ArrayList<>();
        int open = -1;
        String body = marked.strip();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == START) {
                open = text.length();
            } else if (c == STOP) {
                if (open >= 0 && text.length() > open) spans.add(new Span(open, text.length()));
                open = -1;
            } else {
                text.append(c);
            }
        }
        if (cutEnd) text.append(ELLIPSIS);
        return new Snippet(text.toString(), spans);
    }
}
