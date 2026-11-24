package com.performantfs.nodes;

import com.performantfs.http.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates every folder of a dropped directory tree in one transaction, mkdir -p style.
 *
 * <p>Only the top-level folders can collide with something that already exists; they are renamed
 * ("Photos (1)") instead of merged, so a drop never mixes into an existing folder. Everything
 * below them is brand new, so each depth level is a single bulk INSERT.
 */
@Service
public class FolderTreeService {

    public static final int MAX_FOLDERS = 20_000;

    /** One folder to create; the key is the case-folded path, so "a/B" and "a/b" are one folder. */
    private record Planned(String key, List<String> segments, String parentKey) {}

    private final JdbcClient db;
    private final NodeRepository nodes;

    FolderTreeService(JdbcClient db, NodeRepository nodes) {
        this.db = db;
        this.nodes = nodes;
    }

    /** Returns path → folder id for every requested path and every implied parent. */
    @Transactional
    public Map<String, UUID> create(UUID ownerId, UUID parentId, List<String> paths) {
        Map<String, Planned> planned = new LinkedHashMap<>();
        Map<String, String> requested = new LinkedHashMap<>(); // raw path → key
        for (String raw : paths) {
            List<String> segments = Names.splitPath(raw);
            if (segments.isEmpty()) throw ApiException.badRequest("Invalid folder path \"" + raw + "\"");
            for (int depth = 1; depth <= segments.size(); depth++) {
                List<String> prefix = segments.subList(0, depth);
                String key = keyOf(prefix);
                planned.putIfAbsent(key, new Planned(key, List.copyOf(prefix), depth > 1 ? keyOf(prefix.subList(0, depth - 1)) : null));
            }
            requested.put(raw, keyOf(segments));
            if (planned.size() > MAX_FOLDERS) throw ApiException.badRequest("At most " + MAX_FOLDERS + " folders per batch");
        }

        nodes.lockTree(ownerId, true);
        nodes.getLiveFolder(ownerId, parentId, false);

        Map<Integer, List<Planned>> byDepth = new TreeMap<>();
        for (Planned p : planned.values()) byDepth.computeIfAbsent(p.segments().size(), d -> new ArrayList<>()).add(p);

        Map<String, UUID> ids = new HashMap<>();
        for (var level : byDepth.entrySet()) {
            if (level.getKey() == 1) {
                for (Planned p : level.getValue()) {
                    ids.put(p.key(), nodes.insert(NewNode.folder(ownerId, parentId, p.segments().getFirst()), ConflictPolicy.RENAME).id());
                }
                continue;
            }
            List<Planned> folders = level.getValue();
            String[] newIds = new String[folders.size()];
            String[] parents = new String[folders.size()];
            String[] names = new String[folders.size()];
            for (int i = 0; i < folders.size(); i++) {
                Planned p = folders.get(i);
                UUID id = UUID.randomUUID();
                ids.put(p.key(), id);
                newIds[i] = id.toString();
                parents[i] = ids.get(p.parentKey()).toString();
                names[i] = p.segments().getLast();
            }
            db.sql("""
                            INSERT INTO nodes (id, owner_id, parent_id, is_folder, name)
                            SELECT id, ?, parent_id, true, name
                            FROM unnest(CAST(? AS uuid[]), CAST(? AS uuid[]), CAST(? AS text[])) AS t(id, parent_id, name)""")
                    .params(ownerId, newIds, parents, names)
                    .update();
        }

        Map<String, UUID> result = new LinkedHashMap<>();
        for (Planned p : planned.values()) result.put(String.join("/", p.segments()), ids.get(p.key()));
        requested.forEach((raw, key) -> result.put(raw, ids.get(key)));
        return result;
    }

    private static String keyOf(List<String> segments) {
        return String.join("/", segments).toLowerCase(Locale.ROOT);
    }
}
