# Design: Media Asset Indexer

## 1. Goals and scope

The service ingests media assets (images and documents) and makes them searchable. It enriches each asset with
extracted metadata, derivatives, ML tags and an embedding vector. It is the indexing backend of a small DAM system and
has no UI, auth or multi-tenancy.

What it must do:

- Accept uploads quickly and do the expensive work asynchronously.
- Run processing as an ordered, configurable workflow per asset type.
- Survive transient failures (a slow ML endpoint, a storage hiccup) without losing events, and isolate failures that
  will never succeed.
- Serve keyword, semantic and faceted queries with low latency.

## 2. Components

| Component | Responsibility |
|---|---|
| **Upload API** (`AssetController`) | Store the original in MinIO, write an `UPLOADED` document, publish an `AssetEvent` |
| **Kafka** `asset-events` | Durable queue of upload notifications. Keyed by `assetId`, 3 partitions |
| **Pipeline workers** (`AssetEventListener` → `PipelineExecutor`) | Consumer threads that run an asset's workflow |
| **Stages** (`ProcessingStage` beans) | `metadata-extraction`, `thumbnail`, `format-conversion`, `auto-tagging`, `embedding` |
| **ML inference** (`/ml/classify`, `/ml/embed`) | Image classification and embedding over HTTP (mock model bundled in the app) |
| **MinIO** | Originals under `originals/{assetId}/{filename}`, derivatives under `derivatives/{assetId}/…` |
| **Elasticsearch** `assets` index | Metadata, tags, extracted text and the `dense_vector` embedding |
| **Kafka** `asset-events.DLT` | Events that failed permanently, with the exception in the record headers |
| **Search API** (`SearchService`) | Keyword / kNN / hybrid queries, filters and facet aggregations |

The ML endpoints sit behind HTTP on purpose, even though the mock runs in the same JVM. This keeps the stages coupled
only to a URL and a JSON contract (`MlApi`). In production, the model would run as its own service with its own
scaling (GPU nodes, batching), and moving it there is a configuration change.

## 3. Data flow

```
client ──POST /api/assets──► Upload API
                               │ 1. put originals/{id}/{file} ───────────────► MinIO
                               │ 2. index {status: UPLOADED, objectKey, …} ──► Elasticsearch
                               │ 3. send AssetEvent(key=id) ─────────────────► Kafka asset-events
                               └─► 202 {assetId}

Kafka asset-events ──► worker thread (one per partition assignment)
   load document (or create one from the event)    status = PROCESSING
   download original from MinIO                     (retried)
   sniff content type → fileType, category
   workflow = workflows[category]
   for stage in workflow:                           (each stage retried independently)
       stage.process(ctx)                           mutates the document, may write derivatives to MinIO
   status = INDEXED, processedAt = now
   index document                                   (retried)
   commit offset                                    (ack-mode: record)
```

### Stages

| Stage | Input | Output |
|---|---|---|
| `metadata-extraction` | original bytes | `sizeBytes`, `width`/`height`, EXIF (make, model, exposure, ISO, lens, GPS, `capturedAt`) via metadata-extractor. PDFs: page count, info dictionary, text via PDFBox. Text files: content. Default title from the filename |
| `thumbnail` | decoded image | JPEG with a longest edge of at most 256px at `derivatives/{id}/thumbnail.jpg`. Transparency is flattened onto white |
| `format-conversion` | decoded image | If the source format is in `source-formats` (PNG/BMP/GIF), a JPEG rendition at `derivatives/{id}/converted.jpg`. Other formats pass through |
| `auto-tagging` | 224px JPEG model input | `POST /ml/classify`. Labels at or above `min-confidence` become `tags` (top `max-tags`), and confidences go to `labels` |
| `embedding` | 224px model input + tags/title, or document text | `POST /ml/embed`, which returns a 128-dim unit vector stored in `embedding` |

The decoded image and the 224px model input are cached in the `ProcessingContext`, so the image is decoded only once
per asset. Auto-tagging runs before embedding so that the tags can feed into the image's text half of the vector.

### Workflows

A workflow is a list of stage names per category in `indexer.pipeline.workflows`. Categories come from the sniffed
content type: magic bytes first, then the extension, then the declared type. Stages are Spring beans looked up by name,
and the executor fails at startup if a workflow references a stage that doesn't exist. Adding a stage takes two steps:
implement `ProcessingStage`, then add its name to a workflow.

### Event contract

```json
{ "eventId": "…", "assetId": "…", "bucket": "assets", "objectKey": "originals/…/x.png",
  "filename": "x.png", "contentType": "image/png", "sizeBytes": 1234, "title": "…", "uploadedAt": "2026-03-02T10:00:00Z" }
```

Only `assetId`, `bucket` and `objectKey` are required. The consumer reads type information from configuration, not
from producer headers, so a producer in any language can publish plain JSON.

## 4. Failure handling

There are two layers:

1. **Per-stage retry** (`BackoffRetrier`). Each stage, the download and the final index write are retried up to
   `max-attempts` times. The delay starts at `initial-interval`, is multiplied by `multiplier` after each attempt, is
   capped at `max-interval`, and gets up to 25% random jitter. The defaults (4 attempts, 500ms ×2, 8s cap) mean a stage
   gives up after roughly 3.5s plus jitter. Retrying a single stage means a flaky ML call doesn't re-run thumbnailing
   or re-upload derivatives.
2. **Dead-letter topic**. When a stage runs out of retries, or throws `NonRetryableProcessingException` (a corrupt
   image or unreadable PDF fails on the first attempt), the listener throws `StageFailedException`. The container's
   `DefaultErrorHandler` has no further retries and hands the record to a `DeadLetterPublishingRecoverer`, which
   publishes it to `asset-events.DLT` with `kafka_dlt-exception-*` and `kafka_dlt-original-*` headers. Records that
   fail deserialization are also sent to the DLT, as raw bytes, through a separate byte-array template.

On permanent failure, the asset document is also set to `FAILED` with the error message, so operators can find it with
`GET /api/search?status=FAILED`. To replay an event, republish the DLT record to `asset-events`. Processing is
idempotent per `assetId`: the document id is the asset id, and derivative keys are deterministic.

Delivery is at-least-once. Offsets are committed per record after the listener returns, so a crash mid-pipeline
re-runs that asset, which is safe because processing is idempotent.

Retries block the consumer thread. That is acceptable at this scale, and `max.poll.interval.ms` is set well above the
worst-case retry time. If stages became slow enough to threaten the poll interval, the next step would be non-blocking
retry topics (`@RetryableTopic`).

## 5. Search indexing strategy

### Mapping (`resources/elasticsearch/asset-index.json`)

- `dynamic: false`. Only declared fields are indexed, so new or unexpected fields in `_source` can't cause a mapping
  explosion.
- **Exact-match and facet fields** use `keyword` with doc values: `tags`, `fileType`, `category`, `status`,
  `contentType`. Filters and terms aggregations run on these.
- **Full-text fields**:
  - `title`: `english` analyzer
  - `content`: `english` analyzer (PDF/text body, truncated to 20k chars)
  - `filename`: a pattern tokenizer that splits on non-alphanumerics, so `beach_sunset_02.png` produces `beach`,
    `sunset`, `02`, `png`
  - `tags.text`: an analyzed subfield, so free text matches tags without giving up exact-match faceting on `tags`
- `exif` is a `flattened` field. Camera metadata has open-ended keys, and `flattened` indexes them as keywords without
  adding one mapping per key.
- `labels` (tag confidences) is `enabled: false`. It is stored for display only.
- `embedding` is a `dense_vector` with 128 dims, `similarity: cosine`, and an `hnsw` index (`m: 16`,
  `ef_construction: 100`). Vectors are unit-normalised by the model.
- One shard and no replicas, sized for a single-node demo. Assets are small documents, so one shard comfortably holds
  millions.

Documents are written with null fields omitted. An asset that is still `UPLOADED` therefore has no `embedding` field
(cosine similarity rejects zero vectors), and kNN never sees it.

### Query types

| Mode | Query |
|---|---|
| **keyword** | `bool { must: multi_match(title^3, tags.text^2.5, filename^2, content; best_fields; fuzziness AUTO, prefix_length 1), filter: [...] }`, with highlighting on `content`/`title` |
| **semantic** | Top-level `knn { field: embedding, query_vector: embed(q), k: from+size, num_candidates: max(100, 5k), filter: [...], similarity: 0.2 }` |
| **hybrid** (default) | Both of the above in one request. Elasticsearch sums the BM25 score and the kNN score (×2 boost) for documents matched by either |
| **similar** | kNN using an existing asset's stored vector, excluding the asset itself |
| **browse** (no `q`) | `match_all` with the filters, sorted by `uploadedAt desc` |

Notes:

- **Filters apply in every mode.** In BM25 queries they go in `bool.filter`. In kNN they are passed as the kNN
  `filter`, so HNSW search is pre-filtered and still returns k hits that match the facets. Post-filtering would return
  fewer than k. Tags combine with AND (one `term` per tag), file types with OR (`terms`), and dates use a `range` on
  `uploadedAt`.
- **Similarity floor.** kNN always returns k neighbours. Without a minimum similarity, a query like "blue sky" would
  still return the k least-bad assets. The floor (cosine ≥ 0.2) keeps unrelated assets out of semantic and hybrid
  results. The similar-asset endpoint has no floor.
- **Score blending.** BM25 scores are unbounded, while kNN scores for cosine are `(1 + cos) / 2`, in the range 0 to 1.
  The ×2 kNN boost is a simple linear blend that lets a strong semantic match outrank a weak keyword match. Reciprocal
  rank fusion would be the next step if tuning the boost became a chore.
- **Facets** are terms aggregations on `tags`, `fileType` and `category`, plus a monthly `date_histogram` on
  `uploadedAt`. They are computed over the matching set (for kNN, over the top-k).

### Latency considerations

- Filters run in filter context, which skips scoring and lets Elasticsearch cache them in its node query cache. Faceted
  browsing repeats the same filters often.
- `_source` excludes `embedding`, `content`, `labels` and `exif` from search hits. The vector alone is 128 floats per
  hit, and none of these fields are needed for a result list.
- The query vector comes from one small HTTP call. Document vectors are precomputed at ingest, so search never runs
  the image model.
- `num_candidates` is bounded (5×k, minimum 100) to trade recall against HNSW work.
- Thumbnails are served from MinIO through `/api/assets/{id}/content?variant=thumbnail`, so result lists never load
  originals.

## 6. Observability

- **Logs**: Spring Boot structured logging in ECS JSON format on stdout. The consumer puts `assetId`, `eventId`,
  `partition` and `offset` in the MDC, and the retrier adds `stage`. As a result, every log line for an asset, including
  retry warnings, can be correlated in a log store. Each stage logs its duration, and indexing logs the total pipeline
  time.
- **Health**:
  - `/health` is liveness. It returns 200 whenever the process is serving.
  - `/ready` is readiness. It pings Elasticsearch, checks the MinIO bucket, and runs a Kafka `describeCluster` with a
    2s timeout. It returns 503 with per-dependency status if any of them fail, which suits a load balancer or a
    Kubernetes readiness probe.

## 7. Trade-offs and limits

- **Mock ML.** The tags and vectors come from pixel statistics and feature hashing, not a learned model. The pipeline,
  the storage contract and the search path are what this project demonstrates. A real CLIP-style model would make
  text-to-image semantic search work properly, and needs only a change of `ml.base-url` and `embedding-dims`.
- **Event source.** The upload API publishes the event itself. MinIO bucket notifications to Kafka would be an
  alternative, but the API path keeps the event schema under our control and writes the `UPLOADED` document first.
- **Single node.** Compose runs one broker, one Elasticsearch node, and replication factor 1. For production you would
  raise the partitions, replicas and consumer concurrency together.
