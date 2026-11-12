package io.github.shammykr.mediaindexer.pipeline;

/**
 * One step of an asset workflow. Stages are Spring beans looked up by {@link #name()}, and workflows in
 * {@code indexer.pipeline.workflows} list stage names per asset category. Stages mutate
 * {@link ProcessingContext#document()}; the executor persists it.
 */
public interface ProcessingStage {

    String name();

    void process(ProcessingContext context) throws Exception;
}
