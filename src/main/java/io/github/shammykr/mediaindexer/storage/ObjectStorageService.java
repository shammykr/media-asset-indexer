package io.github.shammykr.mediaindexer.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Thin wrapper around the MinIO (S3-compatible) client. Asset binaries live here; Elasticsearch only stores keys. */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public ObjectStorageService(IndexerProperties properties) {
        IndexerProperties.Storage cfg = properties.storage();
        this.client = MinioClient.builder()
                .endpoint(cfg.endpoint())
                .credentials(cfg.accessKey(), cfg.secretKey())
                .build();
        this.bucket = cfg.bucket();
    }

    public String bucket() {
        return bucket;
    }

    public void put(String key, byte[] data, String contentType) throws Exception {
        ensureBucket();
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .contentType(contentType)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .build());
    }

    public byte[] get(String bucketName, String key) throws Exception {
        try (InputStream in = open(bucketName, key)) {
            return in.readAllBytes();
        }
    }

    public InputStream open(String bucketName, String key) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucketName).object(key).build());
    }

    /** Used by the readiness probe. */
    public boolean isAvailable() {
        try {
            client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (!bucketReady) {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Created bucket {}", bucket);
                }
                bucketReady = true;
            }
        }
    }
}
