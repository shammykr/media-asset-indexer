package io.github.shammykr.mediaindexer.health;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import io.github.shammykr.mediaindexer.index.AssetIndexService;
import io.github.shammykr.mediaindexer.storage.ObjectStorageService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Readiness: returns 200 only when Elasticsearch, MinIO and Kafka are all reachable, otherwise 503. */
@RestController
public class ReadinessController {

    private final AssetIndexService index;
    private final ObjectStorageService storage;
    private final KafkaAdmin kafkaAdmin;

    public ReadinessController(AssetIndexService index, ObjectStorageService storage, KafkaAdmin kafkaAdmin) {
        this.index = index;
        this.storage = storage;
        this.kafkaAdmin = kafkaAdmin;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("elasticsearch", index.isAvailable() ? "UP" : "DOWN");
        checks.put("objectStorage", storage.isAvailable() ? "UP" : "DOWN");
        checks.put("kafka", kafkaAvailable() ? "UP" : "DOWN");
        boolean ready = checks.values().stream().allMatch("UP"::equals);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "READY" : "NOT_READY");
        body.put("checks", checks);
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean kafkaAvailable() {
        Properties props = new Properties();
        props.putAll(kafkaAdmin.getConfigurationProperties());
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000);
        try (AdminClient admin = AdminClient.create(props)) {
            return !admin.describeCluster().nodes().get(2, TimeUnit.SECONDS).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
