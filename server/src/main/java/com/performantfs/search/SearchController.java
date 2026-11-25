package com.performantfs.search;

import com.performantfs.auth.AuthUser;
import com.performantfs.nodes.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    private final SearchRepository search;

    SearchController(SearchRepository search) {
        this.search = search;
    }

    @GetMapping("/api/search")
    Page<SearchHit> search(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam @Size(max = 500) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        SearchQuery query = SearchParser.parse(q);
        if (query.isEmpty()) return new Page<>(List.of(), null);
        return search.search(user.id(), query, limit, cursor);
    }
}
