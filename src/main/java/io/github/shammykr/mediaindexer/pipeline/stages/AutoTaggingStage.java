package io.github.shammykr.mediaindexer.pipeline.stages;

import java.util.List;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.ml.MlApi.Prediction;
import io.github.shammykr.mediaindexer.ml.MlClient;
import io.github.shammykr.mediaindexer.pipeline.ProcessingContext;
import io.github.shammykr.mediaindexer.pipeline.ProcessingStage;
import org.springframework.stereotype.Component;

/** Sends a downscaled copy of the image to the classification endpoint and keeps confident labels as tags. */
@Component
public class AutoTaggingStage implements ProcessingStage {

    private final MlClient ml;
    private final IndexerProperties.Tagging cfg;

    public AutoTaggingStage(MlClient ml, IndexerProperties properties) {
        this.ml = ml;
        this.cfg = properties.pipeline().tagging();
    }

    @Override
    public String name() {
        return "auto-tagging";
    }

    @Override
    public void process(ProcessingContext ctx) throws Exception {
        List<Prediction> kept = ml.classify(ctx.modelInput()).stream()
                .filter(p -> p.confidence() >= cfg.minConfidence())
                .limit(cfg.maxTags())
                .toList();
        AssetDocument doc = ctx.document();
        doc.setTags(kept.stream().map(Prediction::label).distinct().toList());
        doc.setLabels(kept.stream().map(p -> new AssetDocument.Label(p.label(), p.confidence())).toList());
    }
}
