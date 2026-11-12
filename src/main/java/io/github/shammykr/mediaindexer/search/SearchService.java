package io.github.shammykr.mediaindexer.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.index.AssetIndexService;
import io.github.shammykr.mediaindexer.ml.MlClient;
import io.github.shammykr.mediaindexer.search.SearchResponse.FacetBucket;
import org.springframework.stereotype.Service;

/**
 * Builds Elasticsearch queries for keyword (BM25 multi_match), semantic (kNN over the dense_vector) and hybrid
 * search, with facet filters applied in filter context and facet counts returned as aggregations.
 */
@Service
public class SearchService {

    /** Relative weight of the kNN clause in hybrid mode (BM25 scores are unbounded, kNN scores are 0..1). */
    private static final float HYBRID_KNN_BOOST = 2.0f;
    /**
     * Minimum cosine similarity for a text-query kNN hit. kNN always returns k neighbours, so without a floor
     * unrelated assets would be padded into results.
     */
    private static final float TEXT_QUERY_MIN_SIMILARITY = 0.2f;
    private static final String[] KEYWORD_FIELDS = {"title^3", "tags.text^2.5", "filename^2", "content"};

    private final ElasticsearchClient es;
    private final AssetIndexService index;
    private final MlClient ml;

    public SearchService(ElasticsearchClient es, AssetIndexService index, MlClient ml) {
        this.es = es;
        this.index = index;
        this.ml = ml;
    }

    public SearchResponse search(SearchCriteria c) throws IOException {
        List<Query> filters = filters(c);
        boolean hasText = c.query() != null && !c.query().isBlank();
        SearchMode mode = hasText ? c.mode() : SearchMode.KEYWORD;
        int from = c.page() * c.size();

        SearchRequest.Builder req = new SearchRequest.Builder()
                .index(index.indexName())
                .from(from)
                .size(c.size())
                .trackTotalHits(t -> t.enabled(true))
                .source(s -> s.filter(f -> f.excludes("embedding", "content", "labels", "exif")));

        if (mode != SearchMode.SEMANTIC) {
            Query main = hasText
                    ? Query.of(q -> q.multiMatch(m -> m.query(c.query()).fields(List.of(KEYWORD_FIELDS))
                            .type(TextQueryType.BestFields).fuzziness("AUTO").prefixLength(1)))
                    : Query.of(q -> q.matchAll(m -> m));
            req.query(q -> q.bool(b -> b.must(main).filter(filters)));
            if (hasText) {
                req.highlight(h -> h
                        .fields("content", f -> f.fragmentSize(160).numberOfFragments(2))
                        .fields("title", f -> f.numberOfFragments(0)));
            } else {
                req.sort(s -> s.field(f -> f.field("uploadedAt").order(SortOrder.Desc)));
            }
        }
        if (mode != SearchMode.KEYWORD) {
            List<Float> vector = toList(ml.embedText(c.query()));
            int k = from + c.size();
            req.knn(knn(vector, k, filters, mode == SearchMode.HYBRID ? HYBRID_KNN_BOOST : 1.0f,
                    TEXT_QUERY_MIN_SIMILARITY));
        }
        addFacets(req);

        return toResponse(es.search(req.build(), AssetDocument.class), mode.name(), c.page(), c.size());
    }

    /** "More like this": kNN using an existing asset's embedding as the query vector. */
    public SearchResponse similar(AssetDocument source, int size) throws IOException {
        List<Float> vector = toList(source.getEmbedding());
        Query notSelf = Query.of(q -> q.bool(b -> b.mustNot(m -> m.ids(i -> i.values(source.getAssetId())))));
        SearchRequest req = new SearchRequest.Builder()
                .index(index.indexName())
                .size(size)
                .source(s -> s.filter(f -> f.excludes("embedding", "content", "labels", "exif")))
                .knn(knn(vector, size, List.of(notSelf), 1.0f, null))
                .build();
        return toResponse(es.search(req, AssetDocument.class), "SIMILAR", 0, size);
    }

    private static KnnSearch knn(List<Float> vector, int k, List<Query> filters, float boost, Float minSimilarity) {
        return KnnSearch.of(n -> {
            n.field("embedding")
                    .queryVector(vector)
                    .k(k)
                    .numCandidates(Math.max(100, k * 5))
                    .filter(filters)
                    .boost(boost);
            if (minSimilarity != null) {
                n.similarity(minSimilarity);
            }
            return n;
        });
    }

    private static List<Query> filters(SearchCriteria c) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("status").value(c.status()))));
        for (String tag : c.tags()) {
            filters.add(Query.of(q -> q.term(t -> t.field("tags").value(tag))));
        }
        if (!c.fileTypes().isEmpty()) {
            List<FieldValue> values = c.fileTypes().stream().map(FieldValue::of).toList();
            filters.add(Query.of(q -> q.terms(t -> t.field("fileType").terms(v -> v.value(values)))));
        }
        if (c.category() != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("category").value(c.category()))));
        }
        if (c.uploadedFrom() != null || c.uploadedTo() != null) {
            filters.add(Query.of(q -> q.range(r -> r.date(d -> {
                d.field("uploadedAt");
                if (c.uploadedFrom() != null) d.gte(c.uploadedFrom());
                if (c.uploadedTo() != null) d.lte(c.uploadedTo());
                return d;
            }))));
        }
        return filters;
    }

    private static void addFacets(SearchRequest.Builder req) {
        req.aggregations("tags", a -> a.terms(t -> t.field("tags").size(25)))
                .aggregations("fileType", a -> a.terms(t -> t.field("fileType").size(10)))
                .aggregations("category", a -> a.terms(t -> t.field("category").size(5)))
                .aggregations("uploadedMonth", a -> a.dateHistogram(d -> d.field("uploadedAt")
                        .calendarInterval(CalendarInterval.Month).format("yyyy-MM").minDocCount(1)));
    }

    private static SearchResponse toResponse(co.elastic.clients.elasticsearch.core.SearchResponse<AssetDocument> r,
                                             String mode, int page, int size) {
        List<SearchResponse.Hit> hits = new ArrayList<>();
        for (Hit<AssetDocument> h : r.hits().hits()) {
            AssetDocument d = h.source();
            if (d == null) continue;
            Double score = h.score() == null || h.score().isNaN() ? null : h.score();
            hits.add(new SearchResponse.Hit(d.getAssetId(), score, d.getTitle(), d.getFilename(), d.getFileType(),
                    d.getCategory(), d.getTags(), d.getWidth(), d.getHeight(), d.getSizeBytes(), d.getUploadedAt(),
                    d.getThumbnailKey() == null ? null : "/api/assets/" + d.getAssetId() + "/content?variant=thumbnail",
                    h.highlight().isEmpty() ? null : h.highlight()));
        }
        Map<String, List<FacetBucket>> facets = new LinkedHashMap<>();
        r.aggregations().forEach((name, agg) -> facets.put(name, buckets(agg)));
        long total = r.hits().total() == null ? hits.size() : r.hits().total().value();
        return new SearchResponse(mode, total, r.took(), page, size, hits, facets);
    }

    private static List<FacetBucket> buckets(Aggregate agg) {
        if (agg.isSterms()) {
            return agg.sterms().buckets().array().stream()
                    .map(b -> new FacetBucket(b.key().stringValue(), b.docCount())).toList();
        }
        if (agg.isDateHistogram()) {
            return agg.dateHistogram().buckets().array().stream()
                    .map(b -> new FacetBucket(b.keyAsString(), b.docCount())).toList();
        }
        return List.of();
    }

    private static List<Float> toList(float[] vector) {
        List<Float> out = new ArrayList<>(vector.length);
        for (float v : vector) out.add(v);
        return out;
    }
}
