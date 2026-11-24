package com.performantfs.nodes;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/** A node plus its ancestors from the root down: {@code {...node, "path": [...]}} on the wire. */
public record NodeWithPath(@JsonUnwrapped NodeDto node, List<Crumb> path) {}
