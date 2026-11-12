package io.github.shammykr.mediaindexer.pipeline;

/** A stage (or the final index write) failed permanently after exhausting its retries. */
public class StageFailedException extends RuntimeException {

    private final String stage;
    private final int attempts;

    public StageFailedException(String stage, int attempts, Throwable cause) {
        super("stage '" + stage + "' failed after " + attempts + " attempt(s): " + cause.getMessage(), cause);
        this.stage = stage;
        this.attempts = attempts;
    }

    public String stage() {
        return stage;
    }

    public int attempts() {
        return attempts;
    }
}
