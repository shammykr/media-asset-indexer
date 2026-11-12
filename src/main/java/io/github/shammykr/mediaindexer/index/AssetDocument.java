package io.github.shammykr.mediaindexer.index;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The document stored in the {@code assets} Elasticsearch index. Binary content is never stored here,
 * only the MinIO object keys for the original and its derivatives.
 */
public class AssetDocument {

    private String assetId;
    private String filename;
    private String title;
    private String contentType;
    private String fileType;
    private String category;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Integer pageCount;
    private Map<String, String> exif = new LinkedHashMap<>();
    private Instant capturedAt;
    private List<String> tags = new ArrayList<>();
    private List<Label> labels = new ArrayList<>();
    private String content;
    private String bucket;
    private String objectKey;
    private String thumbnailKey;
    private String convertedKey;
    private String convertedFormat;
    private AssetStatus status;
    private String error;
    private List<String> stagesCompleted = new ArrayList<>();
    private Instant uploadedAt;
    private Instant processedAt;
    private float[] embedding;

    /** A tag with the classifier's confidence. Stored in _source only (not indexed). */
    public record Label(String name, double confidence) {}

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public Map<String, String> getExif() { return exif; }
    public void setExif(Map<String, String> exif) { this.exif = exif; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public List<Label> getLabels() { return labels; }
    public void setLabels(List<Label> labels) { this.labels = labels; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getThumbnailKey() { return thumbnailKey; }
    public void setThumbnailKey(String thumbnailKey) { this.thumbnailKey = thumbnailKey; }
    public String getConvertedKey() { return convertedKey; }
    public void setConvertedKey(String convertedKey) { this.convertedKey = convertedKey; }
    public String getConvertedFormat() { return convertedFormat; }
    public void setConvertedFormat(String convertedFormat) { this.convertedFormat = convertedFormat; }
    public AssetStatus getStatus() { return status; }
    public void setStatus(AssetStatus status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public List<String> getStagesCompleted() { return stagesCompleted; }
    public void setStagesCompleted(List<String> stagesCompleted) { this.stagesCompleted = stagesCompleted; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }
}
