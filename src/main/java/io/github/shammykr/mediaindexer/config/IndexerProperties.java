package io.github.shammykr.mediaindexer.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All service-specific configuration, bound from the {@code indexer.*} namespace. */
@ConfigurationProperties(prefix = "indexer")
public record IndexerProperties(
        Storage storage,
        Topics topics,
        Index index,
        Ml ml,
        Pipeline pipeline) {

    public record Storage(String endpoint, String accessKey, String secretKey, String bucket) {}

    public record Topics(String events, String deadLetter, int partitions) {}

    public record Index(String name, int embeddingDims) {}

    public record Ml(String baseUrl, Duration timeout) {}

    public record Pipeline(
            Map<String, List<String>> workflows,
            Retry retry,
            Thumbnail thumbnail,
            Conversion conversion,
            Tagging tagging,
            Document document) {}

    public record Retry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {}

    public record Thumbnail(int maxSize, float quality) {}

    public record Conversion(List<String> sourceFormats, String targetFormat, float quality) {}

    public record Tagging(double minConfidence, int maxTags) {}

    public record Document(int maxContentChars) {}
}
