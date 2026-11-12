# Media Asset Indexer

A media asset ingestion and indexing service: the backend of a simplified Digital Asset Management (DAM) platform.

You upload images and documents. The binaries are stored in MinIO (S3-compatible), and an upload event goes to Kafka. A
consumer runs each asset through a configurable processing pipeline:

- metadata and EXIF extraction
- thumbnail generation
- format conversion
- ML auto-tagging
- embedding generation

The result is indexed in Elasticsearch, where you can query it by keyword, by semantic kNN vector search, or with a
hybrid of both, with faceted filtering on tag, file type, category and upload date.

**Stack:** Java 17, Spring Boot 3.5, Apache Kafka (with Zookeeper), Elasticsearch 8 (`dense_vector` + kNN), MinIO, Docker Compose.

## Architecture

```
            POST /api/assets                                   GET /api/search
                  │                                                   │
                  ▼                                                   ▼
        ┌──────────────────┐   put original   ┌─────────┐   ┌──────────────────┐
        │  Upload API      │ ───────────────► │  MinIO  │   │  Search API      │
        └────────┬─────────┘                  └────▲────┘   │ keyword / kNN /  │
                 │ AssetEvent (key = assetId)      │        │ hybrid + facets  │
                 ▼                                 │        └────────┬─────────┘
        ┌──────────────────┐                       │                 │
        │ Kafka            │                       │                 │
        │ asset-events     │──┐                    │                 │
        └──────────────────┘  │ consume            │ get / put       │ query
                              ▼                    │ derivatives     │
        ┌──────────────────────────────────────────┴───┐             │
        │ Pipeline workers (consumer threads)          │             │
        │  metadata-extraction → thumbnail →           │             │
        │  format-conversion → auto-tagging →          │──── index ──┤
        │  embedding        (per-stage retry+backoff)  │             ▼
        └───────┬──────────────────────┬───────────────┘   ┌──────────────────┐
                │ retries exhausted    │ HTTP              │  Elasticsearch   │
                ▼                      ▼                   │  assets index    │
        ┌──────────────────┐   ┌──────────────────┐        │  (BM25 + HNSW)   │
        │ asset-events.DLT │   │ ML inference     │        └──────────────────┘
        └──────────────────┘   │ /ml/classify     │
                               │ /ml/embed (mock) │
                               └──────────────────┘
```

See [DESIGN.md](DESIGN.md) for the data flow, failure handling and search indexing strategy.

## Features

| Feature | Where |
|---|---|
| Kafka ingestion pipeline: a consumer reads upload events and dispatches them through configurable stages | `ingest/AssetEventListener`, `pipeline/PipelineExecutor` |
| ML auto-tagging: calls an image classification endpoint and stores the tags and confidences on the asset | `pipeline/stages/AutoTaggingStage`, `ml/` |
| Embedding generation: a 128-dim vector stored in an Elasticsearch `dense_vector` field | `pipeline/stages/EmbeddingStage` |
| Keyword (BM25), semantic (kNN), hybrid and similar-asset search | `search/SearchService` |
| Binaries in MinIO; the index stores only object keys | `storage/ObjectStorageService` |
| Lifecycle workflows: metadata/EXIF extraction, thumbnails, PNG/BMP/GIF → JPEG conversion | `pipeline/stages/*`, `indexer.pipeline.workflows` |
| Per-stage retry with exponential backoff and jitter, then a dead-letter topic | `pipeline/BackoffRetrier`, `config/KafkaConfig` |
| Faceted filtering (tag, file type, category, date range) with relevance ranking | `GET /api/search` |
| Structured JSON logs (ECS) with `assetId` and `stage` in the MDC; `/health` and `/ready` | `application.yml`, `health/` |

### About the ML model

The classifier and embedding model are a deterministic mock (`ml/MockVisionModel`), served over HTTP at `/ml/classify`
and `/ml/embed`. The mock does analyse real pixels:

- **Tags** come from the hue distribution, brightness, saturation, edge density and region colours. Example tags:
  `blue`, `sky`, `vegetation`, `sunset`, `dark`, `monochrome`, `landscape`, `portrait`.
- **Embeddings** combine two halves:
  - a 64-dim RGB colour histogram, so visually similar images end up as neighbours
  - 64 dims of hashed text features (tags, title and document text), so a text query can be embedded into the same space

The stages only talk to `indexer.ml.base-url`. To use a real model server (for example ONNX Runtime or TorchServe),
point that URL at a server that speaks the same JSON (see `ml/MlApi`). You also need to set
`indexer.index.embedding-dims` to that model's vector size.

## Running

Requirements: Docker with Compose. Give Docker about 4 GB of RAM, because Elasticsearch alone takes 1 GB of heap.

```bash
docker compose up -d --build
curl localhost:8080/ready    # {"status":"READY","checks":{"elasticsearch":"UP","objectStorage":"UP","kafka":"UP"}}
```

| Service | Port |
|---|---|
| App (REST API) | 8080 |
| Elasticsearch | 9200 |
| MinIO API / console | 9000 / 9001 (`minioadmin` / `minioadmin`) |
| Kafka (host listener) | 29092 |

The official `minio/minio` image is no longer published, so Compose uses Bitnami's build of the same MinIO release
(`bitnamilegacy/minio:2025.4.22`).

To run the app on the host instead (Java 17+ and Maven), start the infrastructure only. The defaults in
`application.yml` point at the published ports.

```bash
docker compose up -d zookeeper kafka elasticsearch minio
mvn spring-boot:run
```

## API

### Upload

```bash
curl -F "file=@sunset.png" -F "title=Beach sunset" localhost:8080/api/assets
# 202 {"assetId":"5acb…","objectKey":"originals/5acb…/sunset.png","status":"UPLOADED", …}
```

Processing is asynchronous. The asset's `status` goes `UPLOADED → PROCESSING → INDEXED` (or `FAILED`, with the error recorded).

```bash
curl localhost:8080/api/assets/{assetId}                               # metadata, EXIF, tags, stage history
curl localhost:8080/api/assets/{assetId}/content?variant=thumbnail    # original | thumbnail | converted
```

### Publishing events directly

Any producer that has already written a binary to MinIO can publish an upload notification itself. Only `assetId`,
`bucket` and `objectKey` are required.

```bash
echo '{"assetId":"ext-1","bucket":"assets","objectKey":"originals/…/photo.png","title":"From another service"}' | \
  docker compose exec -T kafka kafka-console-producer --bootstrap-server kafka:9092 --topic asset-events
```

### Search

```
GET /api/search
    q         free text (optional; without it the endpoint browses the newest assets)
    mode      keyword | semantic | hybrid (default hybrid)
    tags      tag filter, comma-separated or repeated; all must match
    fileType  e.g. jpeg,png,pdf; any may match
    category  image | document | other
    from, to  upload date range (ISO date or instant)
    status    default INDEXED
    page, size
```

```bash
curl 'localhost:8080/api/search?q=sunset&mode=keyword'
curl 'localhost:8080/api/search?q=blue+sky&mode=semantic'
curl 'localhost:8080/api/search?q=vegetation&fileType=png,bmp&from=2026-01-01'
curl 'localhost:8080/api/search?tags=landscape&category=image'
curl 'localhost:8080/api/search/similar/{assetId}?size=5'          # more-like-this by embedding
```

Each response includes ranked `results` (with highlights for keyword matches) and `facets`. The facets are counts by
`tags`, `fileType`, `category` and `uploadedMonth` over the matching set.

### Operations

| Endpoint | Purpose |
|---|---|
| `GET /health` | Liveness: the process is up |
| `GET /ready` | Readiness: Elasticsearch, MinIO and Kafka are reachable (returns 503 otherwise) |

Inspect failed messages:

```bash
docker compose exec kafka kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic asset-events.DLT --from-beginning --property print.headers=true
```

## Configuration

Everything lives in `src/main/resources/application.yml` under `indexer.*`. The main settings:

```yaml
indexer:
  pipeline:
    workflows:                      # ordered stages per asset category
      image: [metadata-extraction, thumbnail, format-conversion, auto-tagging, embedding]
      document: [metadata-extraction, embedding]
      other: [metadata-extraction]
    retry: { max-attempts: 4, initial-interval: 500ms, multiplier: 2.0, max-interval: 8s }
    conversion: { source-formats: [png, bmp, gif], target-format: jpeg, quality: 0.85 }
    tagging: { min-confidence: 0.3, max-tags: 10 }
```

Workflows are validated at startup. A workflow that names an unknown stage stops the app from starting.

## Layout

```
src/main/java/io/github/shammykr/mediaindexer/
  config/      properties, Kafka topics + dead-letter error handler, Elasticsearch JSON mapper
  ingest/      upload API, AssetEvent, Kafka publisher and listener
  pipeline/    executor, retry with backoff, processing context, media type detection
    stages/    metadata-extraction, thumbnail, format-conversion, auto-tagging, embedding
  ml/          mock inference endpoints and the HTTP client used by stages and search
  storage/     MinIO wrapper
  index/       asset document, index creation and reads/writes
  search/      search API and query building
  health/      /health and /ready
src/main/resources/elasticsearch/asset-index.json   index settings and mapping
```
