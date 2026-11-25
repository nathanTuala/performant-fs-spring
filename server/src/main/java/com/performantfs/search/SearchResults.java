package com.performantfs.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A page of keyword results and, on the first page only, files related by meaning.
 *
 * @param items      files containing the words (in the name or the contents), ranked
 * @param nextCursor where the next page of {@code items} starts; null on the last page
 * @param related    files close in meaning that aren't in {@code items}; absent after page one
 */
public record SearchResults(
        List<SearchHit> items,
        String nextCursor,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<SearchHit> related) {}
