package com.performantfs.nodes;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** What to do when a sibling already has the name: fail with 409, or take "name (n)". */
public enum ConflictPolicy {
    ERROR,
    RENAME;

    @JsonCreator
    public static ConflictPolicy from(String value) {
        return value == null ? ERROR : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
