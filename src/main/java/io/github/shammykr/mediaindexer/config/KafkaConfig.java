package io.github.shammykr.mediaindexer.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Topics and failure routing. Retries with exponential backoff happen per stage inside the pipeline
 * ({@code BackoffRetrier}); once a stage is out of retries the listener throws and this error handler
 * publishes the record to the dead-letter topic with the exception in the record headers.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    NewTopic assetEventsTopic(IndexerProperties properties) {
        return TopicBuilder.name(properties.topics().events()).partitions(properties.topics().partitions()).replicas(1).build();
    }

    @Bean
    NewTopic assetEventsDeadLetterTopic(IndexerProperties properties) {
        return TopicBuilder.name(properties.topics().deadLetter()).partitions(1).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> jsonTemplate,
                                          ProducerFactory<Object, Object> producerFactory,
                                          IndexerProperties properties) {
        // Records that failed deserialization carry raw bytes; publish those as-is.
        KafkaTemplate<Object, Object> bytesTemplate = new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);

        String dlt = properties.topics().deadLetter();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates, (record, ex) -> {
            log.error("Sending record key={} offset={} to {}: {}", record.key(), record.offset(), dlt,
                    ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
            return new TopicPartition(dlt, -1);
        });
        // No container-level retries: stage retries already happened before the exception reached here.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
    }
}
