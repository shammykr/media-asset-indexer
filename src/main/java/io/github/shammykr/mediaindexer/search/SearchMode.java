package io.github.shammykr.mediaindexer.search;

/** KEYWORD = BM25 only, SEMANTIC = kNN only, HYBRID = both, scores summed by Elasticsearch. */
public enum SearchMode {
    KEYWORD,
    SEMANTIC,
    HYBRID
}
