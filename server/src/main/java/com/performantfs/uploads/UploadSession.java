package com.performantfs.uploads;

import com.performantfs.nodes.NodeDto;
import java.util.List;
import java.util.UUID;

/**
 * An upload session as the API returns it.
 *
 * @param received indexes of the chunks the server already holds
 * @param status   open, completing, done or cancelled
 * @param node     the created file, once status is done
 */
public record UploadSession(UUID uploadId, int chunkSize, int chunkCount, List<Integer> received, String status, NodeDto node) {}
