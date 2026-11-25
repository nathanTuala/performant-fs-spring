package com.performantfs.files;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a Range header asks of a resource (RFC 9110 §14). */
public sealed interface ByteRange {

    /** No usable range: serve the whole resource with 200. */
    record Full() implements ByteRange {}

    /** Bytes {@code start..end} inclusive: serve 206. */
    record Part(long start, long end) implements ByteRange {
        public long length() {
            return end - start + 1;
        }
    }

    /** Well-formed but outside the resource: answer 416. */
    record Unsatisfiable() implements ByteRange {}

    Pattern SINGLE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    /**
     * Parses a single byte range against a resource of {@code size} bytes. Headers we can't parse,
     * other units and multi-range requests all mean "send the whole thing", which the RFC allows.
     */
    static ByteRange parse(String header, long size) {
        if (header == null) return new Full();
        Matcher m = SINGLE.matcher(header.strip());
        if (!m.matches()) return new Full();
        String rawStart = m.group(1);
        String rawEnd = m.group(2);
        if (rawStart.isEmpty() && rawEnd.isEmpty()) return new Full();
        try {
            if (rawStart.isEmpty()) { // suffix range: the last N bytes
                long length = Long.parseLong(rawEnd);
                if (length == 0 || size == 0) return new Unsatisfiable();
                return new Part(Math.max(0, size - length), size - 1);
            }
            long start = Long.parseLong(rawStart);
            if (!rawEnd.isEmpty() && Long.parseLong(rawEnd) < start) return new Full(); // invalid: ignore
            if (start >= size) return new Unsatisfiable();
            long end = rawEnd.isEmpty() ? size - 1 : Math.min(Long.parseLong(rawEnd), size - 1);
            return new Part(start, end);
        } catch (NumberFormatException e) { // more digits than a long holds
            return new Full();
        }
    }
}
