package com.performantfs.search;

import com.performantfs.embedding.Embedder;
import com.performantfs.nodes.Crumb;
import com.performantfs.nodes.NodeRepository;
import com.performantfs.nodes.NodeRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds files whose contents are close in meaning to the query, even when they share none of its
 * words: "files about undecidable problems" finds a PDF on the halting problem. The query is
 * embedded like a passage, and the nearest passages come from the HNSW index on blob_chunks.
 *
 * <p>Passages are shared between users with identical bytes, so the scan keeps only passages of
 * the searcher's own live files, and pgvector's iterative scan keeps going until enough survive
 * that filter. A file is represented by its best passage, which is shown as the snippet.
 */
@Repository
public class SemanticSearch {

    /**
     * Cosine distance (1 − similarity) beyond which a passage isn't considered related. Measured
     * on real files with MiniLM: on-topic questions landed at 0.50–0.63 from the right passage,
     * unrelated ones at 0.72 and above.
     */
    static final double MAX_DISTANCE = 0.65;
    /** Passages fetched before grouping by file; more than enough for a few distinct files. */
    private static final int CANDIDATES = 100;
    private static final int SNIPPET_CHARS = 220;

    private final JdbcClient db;
    private final NodeRepository nodes;
    private final Embedder embedder;
    private final TransactionTemplate tx;

    SemanticSearch(JdbcClient db, NodeRepository nodes, Embedder embedder, TransactionTemplate tx) {
        this.db = db;
        this.nodes = nodes;
        this.embedder = embedder;
        this.tx = tx;
    }

    /** Up to {@code limit} files related in meaning to the query, closest first, minus {@code exclude}. */
    public List<SearchHit> related(UUID ownerId, SearchQuery q, Collection<UUID> exclude, int limit) {
        String text = (q.text() + " " + String.join(" ", q.phrases())).strip();
        if (text.isEmpty()) return List.of();

        Map<String, Object> params = new HashMap<>();
        params.put("owner", ownerId);
        params.put("q", ContentIndexer.vectorLiteral(embedder.embed(text)));
        params.put("candidates", CANDIDATES);
        params.put("max", MAX_DISTANCE);
        SearchFilters filters = SearchFilters.of(q, params);
        List<String> where = new ArrayList<>(List.of(
                "n.owner_id = :owner", "n.deleted_at IS NULL", "n.parent_id IS NOT NULL", "nr.distance <= :max"));
        where.addAll(filters.where());

        String sql = """
                WITH %s nearest AS (
                  SELECT c.hash, c.text, c.embedding <=> CAST(:q AS vector) AS distance
                  FROM blob_chunks c
                  WHERE EXISTS (SELECT 1 FROM nodes o WHERE o.blob_hash = c.hash AND o.owner_id = :owner AND o.deleted_at IS NULL)
                  ORDER BY c.embedding <=> CAST(:q AS vector)
                  LIMIT :candidates
                )
                SELECT DISTINCT ON (n.id) %s, nr.text AS passage, nr.distance
                FROM nearest nr JOIN nodes n ON n.blob_hash = nr.hash
                WHERE %s
                ORDER BY n.id, nr.distance""".formatted(
                filters.scope() == null ? "" : "RECURSIVE " + filters.scope() + ",",
                NodeRow.columns("n"), String.join(" AND ", where));

        record Hit(NodeRow node, String passage, double distance) {}
        List<Hit> hits = tx.execute(status -> {
            // Keep scanning the index past passages the owner filter drops; scoped to this transaction.
            db.sql("SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true), set_config('hnsw.ef_search', '200', true)")
                    .query((rs, n) -> null).list();
            return db.sql(sql).params(params)
                    .query((rs, n) -> new Hit(NodeRow.MAPPER.mapRow(rs, n), rs.getString("passage"), rs.getDouble("distance")))
                    .list();
        });

        List<Hit> best = hits.stream()
                .filter(h -> !exclude.contains(h.node().id()))
                .sorted(Comparator.comparingDouble(Hit::distance))
                .limit(limit)
                .toList();
        Map<UUID, List<Crumb>> paths = nodes.paths(best.stream().map(h -> h.node().id()).toList());
        return best.stream()
                .map(h -> new SearchHit(h.node().toDto(), paths.get(h.node().id()), Snippet.excerpt(h.passage(), SNIPPET_CHARS)))
                .toList();
    }
}
