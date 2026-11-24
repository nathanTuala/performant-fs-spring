package com.performantfs.nodes;

import java.util.UUID;

/** One step of a breadcrumb path. */
public record Crumb(UUID id, String name) {}
