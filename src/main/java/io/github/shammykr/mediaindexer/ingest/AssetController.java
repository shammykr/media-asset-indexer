package io.github.shammykr.mediaindexer.ingest;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.index.AssetIndexService;
import io.github.shammykr.mediaindexer.index.AssetStatus;
import io.github.shammykr.mediaindexer.pipeline.MediaTypes;
import io.github.shammykr.mediaindexer.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Upload and retrieval. An upload stores the binary in MinIO, records an UPLOADED document, and publishes an
 * {@link AssetEvent}; everything else happens asynchronously in the Kafka consumer.
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    private final ObjectStorageService storage;
    private final AssetIndexService index;
    private final AssetEventPublisher publisher;

    public AssetController(ObjectStorageService storage, AssetIndexService index, AssetEventPublisher publisher) {
        this.storage = storage;
        this.index = index;
        this.publisher = publisher;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestPart("file") MultipartFile file,
                                                      @RequestParam(value = "title", required = false) String title)
            throws Exception {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty");
        }
        String assetId = UUID.randomUUID().toString();
        String filename = sanitize(file.getOriginalFilename());
        byte[] bytes = file.getBytes();
        String contentType = MediaTypes.detectContentType(bytes, filename, file.getContentType());
        String objectKey = "originals/" + assetId + "/" + filename;
        Instant now = Instant.now();

        storage.put(objectKey, bytes, contentType);

        AssetDocument doc = new AssetDocument();
        doc.setAssetId(assetId);
        doc.setFilename(filename);
        doc.setTitle(title);
        doc.setContentType(contentType);
        doc.setFileType(MediaTypes.fileType(contentType));
        doc.setCategory(MediaTypes.category(contentType));
        doc.setSizeBytes((long) bytes.length);
        doc.setBucket(storage.bucket());
        doc.setObjectKey(objectKey);
        doc.setStatus(AssetStatus.UPLOADED);
        doc.setUploadedAt(now);
        index.save(doc);

        publisher.publish(new AssetEvent(UUID.randomUUID().toString(), assetId, storage.bucket(), objectKey, filename,
                contentType, (long) bytes.length, title, now));
        log.info("Accepted upload assetId={} key={} bytes={}", assetId, objectKey, bytes.length);

        return ResponseEntity.accepted().body(Map.of(
                "assetId", assetId,
                "objectKey", objectKey,
                "status", AssetStatus.UPLOADED,
                "links", Map.of("self", "/api/assets/" + assetId)));
    }

    @GetMapping("/{assetId}")
    public AssetDocument get(@PathVariable String assetId) throws Exception {
        AssetDocument doc = index.find(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "asset not found"));
        doc.setEmbedding(null); // large and not useful to API clients
        return doc;
    }

    /** Streams the original or a derivative (thumbnail, converted rendition) from object storage. */
    @GetMapping("/{assetId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String assetId,
                                                       @RequestParam(defaultValue = "original") String variant)
            throws Exception {
        AssetDocument doc = index.find(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "asset not found"));
        String key;
        String type;
        switch (variant) {
            case "original" -> { key = doc.getObjectKey(); type = doc.getContentType(); }
            case "thumbnail" -> { key = doc.getThumbnailKey(); type = "image/jpeg"; }
            case "converted" -> { key = doc.getConvertedKey(); type = "image/" + doc.getConvertedFormat(); }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variant must be original, thumbnail or converted");
        }
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, variant + " not available for this asset");
        }
        InputStream in = storage.open(doc.getBucket(), key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type == null ? "application/octet-stream" : type))
                .body(new InputStreamResource(in));
    }

    private static String sanitize(String original) {
        String name = original == null || original.isBlank() ? "upload" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
