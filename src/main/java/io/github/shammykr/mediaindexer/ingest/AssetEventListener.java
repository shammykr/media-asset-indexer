package io.github.shammykr.mediaindexer.ingest;

import io.github.shammykr.mediaindexer.pipeline.PipelineExecutor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes upload notifications and dispatches them through the processing pipeline. Concurrency is set by
 * {@code spring.kafka.listener.concurrency}; each consumer thread is an async worker over its partitions.
 */
@Component
public class AssetEventListener {

    private static final Logger log = LoggerFactory.getLogger(AssetEventListener.class);

    private final PipelineExecutor pipeline;

    public AssetEventListener(PipelineExecutor pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(topics = "${indexer.topics.events}")
    public void onAssetEvent(ConsumerRecord<String, AssetEvent> record) {
        AssetEvent event = record.value();
        if (event == null || event.assetId() == null || event.bucket() == null || event.objectKey() == null) {
            throw new IllegalArgumentException("asset event is missing assetId, bucket or objectKey");
        }
        MDC.put("assetId", event.assetId());
        MDC.put("eventId", String.valueOf(event.eventId()));
        MDC.put("partition", Integer.toString(record.partition()));
        MDC.put("offset", Long.toString(record.offset()));
        try {
            log.info("Received asset event for {}", event.objectKey());
            pipeline.process(event);
        } finally {
            MDC.clear();
        }
    }
}
