package io.github.shammykr.mediaindexer.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SearchResponse(
        String mode,
        long total,
        long tookMs,
        int page,
        int size,
        List<Hit> results,
        Map<String, List<FacetBucket>> facets) {

    public record Hit(
            String assetId,
            Double score,
            String title,
            String filename,
            String fileType,
            String category,
            List<String> tags,
            Integer width,
            Integer height,
            Long sizeBytes,
            Instant uploadedAt,
            String thumbnailUrl,
            Map<String, List<String>> highlights) {}

    public record FacetBucket(String value, long count) {}
}
