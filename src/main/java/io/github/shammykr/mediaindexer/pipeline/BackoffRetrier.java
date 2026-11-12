package io.github.shammykr.mediaindexer.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Retries a single pipeline step with exponential backoff and jitter. Retrying per stage means a flaky
 * ML call does not force metadata extraction and thumbnailing to run again.
 */
@Component
public class BackoffRetrier {

    private static final Logger log = LoggerFactory.getLogger(BackoffRetrier.class);

    private final IndexerProperties.Retry cfg;

    public BackoffRetrier(IndexerProperties properties) {
        this.cfg = properties.pipeline().retry();
    }

    public <T> T call(String step, Callable<T> action) {
        long delay = cfg.initialInterval().toMillis();
        MDC.put("stage", step);
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    return action.call();
                } catch (NonRetryableProcessingException e) {
                    throw new StageFailedException(step, attempt, e);
                } catch (Exception e) {
                    if (attempt >= cfg.maxAttempts()) {
                        throw new StageFailedException(step, attempt, e);
                    }
                    long sleep = delay + ThreadLocalRandom.current().nextLong(delay / 4 + 1);
                    log.warn("Step {} failed (attempt {}/{}), retrying in {} ms: {}",
                            step, attempt, cfg.maxAttempts(), sleep, e.toString());
                    sleepQuietly(sleep);
                    delay = Math.min((long) (delay * cfg.multiplier()), cfg.maxInterval().toMillis());
                }
            }
        } finally {
            MDC.remove("stage");
        }
    }

    public void run(String step, ThrowingRunnable action) {
        call(step, () -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during retry backoff", e);
        }
    }
}
