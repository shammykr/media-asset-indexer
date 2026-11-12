package io.github.shammykr.mediaindexer.ingest;

import java.time.Instant;

/**
 * Upload notification consumed from the {@code asset-events} topic. Any producer can publish one once the
 * binary is in object storage; only {@code assetId}, {@code bucket} and {@code objectKey} are required.
 */
public record AssetEvent(
        String eventId,
        String assetId,
        String bucket,
        String objectKey,
        String filename,
        String contentType,
        Long sizeBytes,
        String title,
        Instant uploadedAt) {}
