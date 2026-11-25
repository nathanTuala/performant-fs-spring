package com.performantfs.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.performantfs.nodes.Crumb;
import com.performantfs.nodes.NodeDto;
import java.util.List;

/**
 * One search result: the node, where it lives, and, when the words were found inside the file,
 * an excerpt showing them.
 */
public record SearchHit(
        @JsonUnwrapped NodeDto node,
        List<Crumb> path,
        @JsonInclude(JsonInclude.Include.NON_NULL) Snippet snippet) {}
