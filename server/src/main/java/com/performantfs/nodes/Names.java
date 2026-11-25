package com.performantfs.nodes;

import com.performantfs.http.ApiException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules for file and folder names, mirroring the CHECK constraint on nodes.name. */
public final class Names {

    public static final int MAX_LENGTH = 255;

    // Lazy stem, then an optional ".tar" and a short extension: "archive.tar.gz" keeps both.
    private static final Pattern STEM_AND_EXTENSION =
            Pattern.compile("^(.+?)((?:\\.tar)?\\.[^.\\s]{1,10})?$", Pattern.CASE_INSENSITIVE);

    private Names() {}

    /**
     * Validates one path segment and returns it trimmed and NFC-normalised, so bad input gets a
     * clear 400 instead of a constraint violation, and "é" typed two ways is one name.
     */
    public static String normalize(String raw) {
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw ApiException.badRequest("Name cannot be empty, \".\" or \"..\"");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) throw ApiException.badRequest("Name cannot contain \"/\"");
        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            throw ApiException.badRequest("Name is longer than " + MAX_LENGTH + " characters");
        }
        return name;
    }

    /** "report.pdf" + 2 → "report (2).pdf"; "archive.tar.gz" + 1 → "archive (1).tar.gz"; ".env" + 1 → ".env (1)". */
    public static String numbered(String name, int n) {
        Matcher m = STEM_AND_EXTENSION.matcher(name);
        if (!m.matches()) return name + " (" + n + ")";
        String ext = m.group(2) == null ? "" : m.group(2);
        return m.group(1) + " (" + n + ")" + ext;
    }

    /** Splits "a/b/c" into normalised segments, ignoring empty ones. */
    public static List<String> splitPath(String path) {
        return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).map(Names::normalize).toList();
    }
}
