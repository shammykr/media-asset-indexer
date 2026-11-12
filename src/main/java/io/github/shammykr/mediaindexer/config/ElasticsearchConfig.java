package io.github.shammykr.mediaindexer.config;

import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configures the {@code ElasticsearchClient} from {@code spring.elasticsearch.uris}.
 * We only override the JSON mapper so documents use Spring's ObjectMapper (java.time support, ISO dates)
 * and omit null fields, which keeps partially-processed documents free of placeholder values.
 */
@Configuration
public class ElasticsearchConfig {

    @Bean
    JsonpMapper elasticsearchJsonpMapper(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return new JacksonJsonpMapper(mapper);
    }
}
