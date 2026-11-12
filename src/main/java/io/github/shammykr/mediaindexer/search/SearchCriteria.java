package io.github.shammykr.mediaindexer.search;

import java.util.List;

/** Parsed search parameters. Filters apply in every mode (as bool filters or as kNN pre-filters). */
public record SearchCriteria(
        String query,
        SearchMode mode,
        List<String> tags,
        List<String> fileTypes,
        String category,
        String status,
        String uploadedFrom,
        String uploadedTo,
        int page,
        int size) {}
