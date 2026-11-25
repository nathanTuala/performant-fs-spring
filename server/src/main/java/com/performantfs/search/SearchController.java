package com.performantfs.search;

import com.performantfs.auth.AuthUser;
import com.performantfs.config.AppProperties;
import com.performantfs.nodes.Page;
import com.performantfs.observability.Observed;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    /** Result ids put on a span: enough to see what came back without bloating the trace. */
    private static final int TRACED_IDS = 10;

    private final SearchRepository search;
    private final SemanticSearch semantic;
    private final ObservationRegistry observations;
    private final AppProperties props;

    SearchController(SearchRepository search, SemanticSearch semantic, ObservationRegistry observations, AppProperties props) {
        this.search = search;
        this.semantic = semantic;
        this.observations = observations;
        this.props = props;
    }

    /**
     * Keyword results, paginated; the first page also carries files related by meaning that the
     * keyword results don't already show. Each stage is a span (and a timer) with what it returned.
     */
    @GetMapping("/api/search")
    SearchResults search(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam @Size(max = 500) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        SearchQuery query = SearchParser.parse(q);
        if (query.isEmpty()) return new SearchResults(List.of(), null, null);
        boolean firstPage = cursor == null || cursor.isEmpty();

        Page<SearchHit> page = Observed.observe(observations, "pfs.search.keyword", o -> {
            describe(o, q, query, firstPage);
            Page<SearchHit> found = search.search(user.id(), query, limit, cursor);
            o.highCardinalityKeyValue("results", String.valueOf(found.items().size()));
            o.highCardinalityKeyValue("more", String.valueOf(found.nextCursor() != null));
            o.highCardinalityKeyValue("result.ids", ids(found.items()));
            return found;
        });
        if (!firstPage) return new SearchResults(page.items(), page.nextCursor(), null);

        Set<UUID> shown = page.items().stream().map(h -> h.node().id()).collect(Collectors.toSet());
        List<SearchHit> related = Observed.observe(observations, "pfs.search.semantic", o -> {
            describe(o, q, query, true);
            List<SearchHit> found = semantic.related(user.id(), query, shown, RELATED);
            o.highCardinalityKeyValue("results", String.valueOf(found.size()));
            o.highCardinalityKeyValue("result.ids", ids(found));
            return found;
        });
        return new SearchResults(page.items(), page.nextCursor(), related);
    }

    /** Low-cardinality tags (safe as metric dimensions), plus the text itself if allowed. */
    private void describe(Observation o, String raw, SearchQuery query, boolean firstPage) {
        o.lowCardinalityKeyValue("page", firstPage ? "first" : "next");
        o.lowCardinalityKeyValue("words", String.valueOf(!query.text().isEmpty() || !query.phrases().isEmpty()));
        o.lowCardinalityKeyValue("filters", String.valueOf(!query.types().isEmpty() || !query.sizes().isEmpty()
                || !query.modified().isEmpty() || query.inFolder() != null));
        if (props.traceSearchText()) o.highCardinalityKeyValue("query", raw);
    }

    private static String ids(List<SearchHit> hits) {
        return hits.stream().limit(TRACED_IDS).map(h -> h.node().id().toString()).collect(Collectors.joining(","));
    }
}
