package com.performantfs.search;

import com.performantfs.auth.AuthUser;
import com.performantfs.nodes.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    /** Related-by-meaning results shown under the first page. */
    static final int RELATED = 8;

    private final SearchRepository search;
    private final SemanticSearch semantic;

    SearchController(SearchRepository search, SemanticSearch semantic) {
        this.search = search;
        this.semantic = semantic;
    }

    /**
     * Keyword results, paginated; the first page also carries files related by meaning that the
     * keyword results don't already show.
     */
    @GetMapping("/api/search")
    SearchResults search(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam @Size(max = 500) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        SearchQuery query = SearchParser.parse(q);
        if (query.isEmpty()) return new SearchResults(List.of(), null, null);
        Page<SearchHit> page = search.search(user.id(), query, limit, cursor);
        if (cursor != null && !cursor.isEmpty()) return new SearchResults(page.items(), page.nextCursor(), null);
        Set<UUID> shown = page.items().stream().map(h -> h.node().id()).collect(Collectors.toSet());
        return new SearchResults(page.items(), page.nextCursor(), semantic.related(user.id(), query, shown, RELATED));
    }
}
