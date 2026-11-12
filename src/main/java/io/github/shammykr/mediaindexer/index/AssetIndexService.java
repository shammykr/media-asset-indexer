package io.github.shammykr.mediaindexer.index;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import io.github.shammykr.mediaindexer.config.IndexerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Owns the {@code assets} index: creates it with the mapping on startup and reads/writes asset documents. */
@Service
public class AssetIndexService {

    private static final Logger log = LoggerFactory.getLogger(AssetIndexService.class);
    private static final String MAPPING_RESOURCE = "elasticsearch/asset-index.json";

    private final ElasticsearchClient es;
    private final String index;
    private final int embeddingDims;

    public AssetIndexService(ElasticsearchClient es, IndexerProperties properties) {
        this.es = es;
        this.index = properties.index().name();
        this.embeddingDims = properties.index().embeddingDims();
    }

    public String indexName() {
        return index;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                if (es.indices().exists(e -> e.index(index)).value()) {
                    log.info("Index {} already exists", index);
                    return;
                }
                try (InputStream mapping = mappingJson()) {
                    es.indices().create(c -> c.index(index).withJson(mapping));
                }
                log.info("Created index {} with {}-dim dense_vector embeddings", index, embeddingDims);
                return;
            } catch (IOException e) {
                if (attempt >= 30) {
                    throw e;
                }
                log.warn("Elasticsearch not reachable yet (attempt {}): {}", attempt, e.getMessage());
                Thread.sleep(2000);
            }
        }
    }

    public void save(AssetDocument doc) throws IOException {
        es.index(i -> i.index(index).id(doc.getAssetId()).document(doc));
    }

    public Optional<AssetDocument> find(String assetId) throws IOException {
        GetResponse<AssetDocument> response = es.get(g -> g.index(index).id(assetId), AssetDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    public boolean isAvailable() {
        try {
            return es.ping().value();
        } catch (Exception e) {
            return false;
        }
    }

    private InputStream mappingJson() throws IOException {
        String json = new ClassPathResource(MAPPING_RESOURCE).getContentAsString(StandardCharsets.UTF_8)
                .replace("\"${embeddingDims}\"", Integer.toString(embeddingDims));
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
