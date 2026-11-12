package io.github.shammykr.mediaindexer.ingest;

import java.util.concurrent.TimeUnit;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes upload notifications keyed by asset id, so all events for one asset land on one partition. */
@Component
public class AssetEventPublisher {

    private final KafkaTemplate<Object, Object> kafka;
    private final String topic;

    public AssetEventPublisher(KafkaTemplate<Object, Object> kafka, IndexerProperties properties) {
        this.kafka = kafka;
        this.topic = properties.topics().events();
    }

    public void publish(AssetEvent event) throws Exception {
        kafka.send(topic, event.assetId(), event).get(10, TimeUnit.SECONDS);
    }
}
