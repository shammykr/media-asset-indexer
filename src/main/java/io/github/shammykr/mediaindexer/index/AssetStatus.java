package io.github.shammykr.mediaindexer.index;

/** Lifecycle of an asset as it moves through ingestion. */
public enum AssetStatus {
    UPLOADED,
    PROCESSING,
    INDEXED,
    FAILED
}
