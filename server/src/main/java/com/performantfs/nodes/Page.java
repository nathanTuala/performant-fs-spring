package com.performantfs.nodes;

import java.util.List;

/** One page of a keyset-paginated list; {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, String nextCursor) {}
