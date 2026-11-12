package io.github.shammykr.mediaindexer.pipeline.stages;

import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.ml.MlClient;
import io.github.shammykr.mediaindexer.pipeline.ProcessingContext;
import io.github.shammykr.mediaindexer.pipeline.ProcessingStage;
import org.springframework.stereotype.Component;

/**
 * Generates the vector stored in the {@code embedding} dense_vector field. Images are embedded from pixels
 * plus their tags/title; documents from title plus extracted text. Runs after auto-tagging in the image workflow.
 */
@Component
public class EmbeddingStage implements ProcessingStage {

    private final MlClient ml;

    public EmbeddingStage(MlClient ml) {
        this.ml = ml;
    }

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public void process(ProcessingContext ctx) throws Exception {
        AssetDocument doc = ctx.document();
        String title = doc.getTitle() == null ? "" : doc.getTitle();
        if ("image".equals(doc.getCategory())) {
            String text = title + " " + String.join(" ", doc.getTags());
            doc.setEmbedding(ml.embedImage(ctx.modelInput(), text));
        } else {
            String body = doc.getContent() == null ? "" : doc.getContent();
            doc.setEmbedding(ml.embedText(title + " " + title + " " + body));
        }
    }
}
