package io.github.shammykr.mediaindexer.search;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.index.AssetIndexService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final int MAX_SIZE = 100;

    private final SearchService search;
    private final AssetIndexService index;

    public SearchController(SearchService search, AssetIndexService index) {
        this.search = search;
        this.index = index;
    }

    /**
     * Example: {@code GET /api/search?q=blue+sky&mode=hybrid&tags=landscape&fileType=jpeg&from=2024-01-01}.
     * Without {@code q}, returns the newest assets matching the filters (faceted browse).
     */
    @GetMapping
    public SearchResponse search(@RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "hybrid") String mode,
                                 @RequestParam(required = false) List<String> tags,
                                 @RequestParam(required = false) List<String> fileType,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(defaultValue = "INDEXED") String status,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) throws Exception {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0 and size in 1.." + MAX_SIZE);
        }
        SearchMode searchMode;
        try {
            searchMode = SearchMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be keyword, semantic or hybrid");
        }
        SearchCriteria criteria = new SearchCriteria(q, searchMode,
                normalize(tags), normalize(fileType), category, status.toUpperCase(Locale.ROOT),
                date(from, "from"), date(to, "to"), page, size);
        return search.search(criteria);
    }

    @GetMapping("/similar/{assetId}")
    public SearchResponse similar(@PathVariable String assetId,
                                  @RequestParam(defaultValue = "10") int size) throws Exception {
        AssetDocument doc = index.find(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "asset not found"));
        if (doc.getEmbedding() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "asset has no embedding yet (status " + doc.getStatus() + ")");
        }
        return search.similar(doc, Math.min(Math.max(size, 1), MAX_SIZE));
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        return values.stream().flatMap(v -> List.of(v.split(",")).stream())
                .map(String::trim).filter(v -> !v.isEmpty()).map(v -> v.toLowerCase(Locale.ROOT)).toList();
    }

    /** Accepts {@code 2024-05-01} or a full ISO instant; passes the original string through to Elasticsearch. */
    private static String date(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() == 10) LocalDate.parse(value);
            else Instant.parse(value);
            return value;
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be an ISO date or instant");
        }
    }
}
