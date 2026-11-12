package io.github.shammykr.mediaindexer.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.index.AssetIndexService;
import io.github.shammykr.mediaindexer.index.AssetStatus;
import io.github.shammykr.mediaindexer.ingest.AssetEvent;
import io.github.shammykr.mediaindexer.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the workflow for one asset: download the original from object storage, run the configured stages
 * for the asset's category in order (each with retry), then write the final document to Elasticsearch.
 */
@Service
public class PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutor.class);

    private final Map<String, ProcessingStage> stages;
    private final Map<String, List<String>> workflows;
    private final BackoffRetrier retrier;
    private final ObjectStorageService storage;
    private final AssetIndexService index;

    public PipelineExecutor(List<ProcessingStage> stageBeans, IndexerProperties properties, BackoffRetrier retrier,
                            ObjectStorageService storage, AssetIndexService index) {
        this.stages = stageBeans.stream().collect(Collectors.toMap(ProcessingStage::name, Function.identity()));
        this.workflows = new LinkedHashMap<>(properties.pipeline().workflows());
        this.retrier = retrier;
        this.storage = storage;
        this.index = index;
        workflows.forEach((category, names) -> names.forEach(name -> {
            if (!stages.containsKey(name)) {
                throw new IllegalStateException("workflow '" + category + "' references unknown stage '" + name
                        + "'; known stages: " + stages.keySet());
            }
        }));
        log.info("Pipeline workflows: {}", workflows);
    }

    public AssetDocument process(AssetEvent event) {
        long started = System.nanoTime();
        AssetDocument doc = retrier.call("load-document", () -> index.find(event.assetId()))
                .orElseGet(() -> fromEvent(event));
        doc.setStatus(AssetStatus.PROCESSING);
        doc.setError(null);
        doc.setStagesCompleted(new ArrayList<>());

        try {
            byte[] original = retrier.call("download", () -> storage.get(event.bucket(), event.objectKey()));
            doc.setSizeBytes((long) original.length);
            doc.setContentType(MediaTypes.detectContentType(original, event.filename(), event.contentType()));
            doc.setFileType(MediaTypes.fileType(doc.getContentType()));
            doc.setCategory(MediaTypes.category(doc.getContentType()));

            ProcessingContext context = new ProcessingContext(event, original, doc);
            List<String> workflow = workflows.getOrDefault(doc.getCategory(), workflows.getOrDefault("other", List.of()));
            for (String name : workflow) {
                ProcessingStage stage = stages.get(name);
                long t0 = System.nanoTime();
                retrier.run(name, () -> stage.process(context));
                doc.getStagesCompleted().add(name);
                log.info("Stage {} completed in {} ms", name, (System.nanoTime() - t0) / 1_000_000);
            }

            doc.setStatus(AssetStatus.INDEXED);
            doc.setProcessedAt(Instant.now());
            retrier.run("index", () -> index.save(doc));
            log.info("Asset indexed: category={} fileType={} tags={} in {} ms", doc.getCategory(), doc.getFileType(),
                    doc.getTags(), (System.nanoTime() - started) / 1_000_000);
            return doc;
        } catch (StageFailedException e) {
            markFailed(doc, e);
            throw e;
        }
    }

    private void markFailed(AssetDocument doc, StageFailedException e) {
        doc.setStatus(AssetStatus.FAILED);
        doc.setError(e.getMessage());
        doc.setProcessedAt(Instant.now());
        try {
            index.save(doc);
        } catch (Exception indexError) {
            log.warn("Could not record FAILED status for asset {}: {}", doc.getAssetId(), indexError.toString());
        }
    }

    private static AssetDocument fromEvent(AssetEvent event) {
        AssetDocument doc = new AssetDocument();
        doc.setAssetId(event.assetId());
        doc.setFilename(event.filename() != null ? event.filename()
                : event.objectKey().substring(event.objectKey().lastIndexOf('/') + 1));
        doc.setTitle(event.title());
        doc.setBucket(event.bucket());
        doc.setObjectKey(event.objectKey());
        doc.setUploadedAt(event.uploadedAt() != null ? event.uploadedAt() : Instant.now());
        return doc;
    }
}
