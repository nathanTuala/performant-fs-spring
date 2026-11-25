package com.performantfs.nodes;

import com.performantfs.http.ApiException;
import java.util.Locale;

/** How a folder listing is ordered. Folders always come first. */
public record Sort(Key key, boolean descending) {

    /** Each key maps to an indexed SQL expression and the type its cursor value casts back to. */
    public enum Key {
        NAME("lower(name)", "text"),
        SIZE("size", "bigint"),
        MODIFIED("updated_at", "timestamptz");

        final String expression;
        final String sqlType;

        Key(String expression, String sqlType) {
            this.expression = expression;
            this.sqlType = sqlType;
        }
    }

    public static Sort parse(String key, String dir) {
        try {
            Key k = Key.valueOf(key.toUpperCase(Locale.ROOT));
            return switch (dir.toLowerCase(Locale.ROOT)) {
                case "asc" -> new Sort(k, false);
                case "desc" -> new Sort(k, true);
                default -> throw ApiException.badRequest("dir must be asc or desc");
            };
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("sort must be name, size or modified");
        }
    }
}
