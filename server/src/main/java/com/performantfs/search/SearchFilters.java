package com.performantfs.search;

import com.performantfs.search.SearchQuery.Comparison;
import com.performantfs.search.SearchQuery.DateFilter;
import com.performantfs.search.SearchQuery.SizeFilter;
import com.performantfs.search.SearchQuery.TypeFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The filter part of a search (type:, size:, modified:, in:) as SQL conditions on {@code nodes n},
 * shared by keyword and semantic search so both narrow results the same way.
 *
 * @param where conditions to AND together
 * @param scope a recursive CTE named {@code scope} (the in: folder and its subfolders), or null
 */
record SearchFilters(List<String> where, String scope) {

    private static final Map<TypeFilter, String> TYPE_SQL = Map.of(
            TypeFilter.FOLDER, "n.is_folder",
            TypeFilter.FILE, "NOT n.is_folder",
            TypeFilter.IMAGE, "n.mime LIKE 'image/%'",
            TypeFilter.VIDEO, "n.mime LIKE 'video/%'",
            TypeFilter.AUDIO, "n.mime LIKE 'audio/%'",
            TypeFilter.PDF, "n.mime = 'application/pdf'",
            TypeFilter.TEXT, "(n.mime LIKE 'text/%' OR n.mime IN ('application/json', 'application/xml'))",
            TypeFilter.DOC, "(n.mime IN ('application/msword', 'application/vnd.ms-excel', 'application/vnd.ms-powerpoint')"
                    + " OR n.mime LIKE 'application/vnd.openxmlformats-officedocument.%')",
            TypeFilter.ARCHIVE, "n.mime IN ('application/zip', 'application/gzip', 'application/x-tar', 'application/x-7z-compressed')");

    /** Adds the filters' values to {@code params} (which must already hold {@code :owner}). */
    static SearchFilters of(SearchQuery q, Map<String, Object> params) {
        List<String> where = new ArrayList<>();
        if (!q.types().isEmpty()) {
            where.add(q.types().stream().map(TYPE_SQL::get).collect(Collectors.joining(" OR ", "(", ")")));
        }
        for (int i = 0; i < q.sizes().size(); i++) {
            SizeFilter f = q.sizes().get(i);
            params.put("size" + i, f.bytes());
            where.add("NOT n.is_folder AND n.size " + f.op().sql + " :size" + i);
        }
        for (int i = 0; i < q.modified().size(); i++) {
            DateFilter f = q.modified().get(i);
            params.put("day" + i, f.day());
            where.add(dateCondition(f.op(), ":day" + i));
        }
        String scope = null;
        if (q.inFolder() != null) {
            // Every folder with that name, and every folder below them. Only folders are walked.
            params.put("folder", q.inFolder());
            scope = """
                    scope AS (
                      SELECT id FROM nodes
                      WHERE owner_id = :owner AND is_folder AND deleted_at IS NULL AND lower(name) = lower(:folder)
                      UNION
                      SELECT c.id FROM nodes c JOIN scope s ON c.parent_id = s.id WHERE c.is_folder AND c.deleted_at IS NULL
                    )""";
            where.add("n.parent_id IN (SELECT id FROM scope)");
        }
        return new SearchFilters(where, scope);
    }

    /** Day-granular: modified:&lt;2026-01-01 means before that day starts. */
    private static String dateCondition(Comparison op, String day) {
        String start = "CAST(" + day + " AS date)";
        String next = "(" + start + " + 1)";
        return switch (op) {
            case LT -> "n.updated_at < " + start;
            case LE -> "n.updated_at < " + next;
            case GT -> "n.updated_at >= " + next;
            case GE -> "n.updated_at >= " + start;
            case EQ -> "n.updated_at >= " + start + " AND n.updated_at < " + next;
        };
    }
}
