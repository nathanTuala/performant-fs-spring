package com.performantfs.search;

import java.time.LocalDate;
import java.util.List;

/**
 * A parsed search: free text plus filters.
 *
 * @param text     free words, matched as whole words, word prefixes, or fuzzily
 * @param phrases  "quoted phrases", matched as substrings of the name
 * @param types    OR-ed together; empty means any type
 * @param inFolder in:"Folder name" restricts results to that folder's subtree
 */
public record SearchQuery(String text, List<String> phrases, List<TypeFilter> types, List<SizeFilter> sizes,
                          List<DateFilter> modified, String inFolder) {

    public enum TypeFilter { FOLDER, FILE, IMAGE, VIDEO, AUDIO, PDF, TEXT, DOC, ARCHIVE }

    public enum Comparison {
        LT("<"), LE("<="), GT(">"), GE(">="), EQ("=");

        final String sql;

        Comparison(String sql) {
            this.sql = sql;
        }

        static Comparison of(String op) {
            if (op == null || op.isEmpty()) return EQ;
            for (Comparison c : values()) if (c.sql.equals(op)) return c;
            throw new IllegalArgumentException(op);
        }
    }

    public record SizeFilter(Comparison op, long bytes) {}

    public record DateFilter(Comparison op, LocalDate day) {}

    public boolean isEmpty() {
        return text.isEmpty() && phrases.isEmpty() && types.isEmpty() && sizes.isEmpty() && modified.isEmpty() && inFolder == null;
    }
}
