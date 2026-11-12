package io.github.shammykr.mediaindexer.pipeline;

/** Thrown by a stage when retrying cannot help (corrupt file, unsupported format). Skips straight to the DLT. */
public class NonRetryableProcessingException extends RuntimeException {

    public NonRetryableProcessingException(String message) {
        super(message);
    }

    public NonRetryableProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
