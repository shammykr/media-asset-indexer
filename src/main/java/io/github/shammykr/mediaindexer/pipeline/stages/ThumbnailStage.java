package io.github.shammykr.mediaindexer.pipeline.stages;

import java.awt.image.BufferedImage;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.github.shammykr.mediaindexer.pipeline.ImageCodec;
import io.github.shammykr.mediaindexer.pipeline.ProcessingContext;
import io.github.shammykr.mediaindexer.pipeline.ProcessingStage;
import io.github.shammykr.mediaindexer.storage.ObjectStorageService;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

/** Writes a bounded-size JPEG thumbnail to {@code derivatives/<assetId>/thumbnail.jpg}. */
@Component
public class ThumbnailStage implements ProcessingStage {

    private final ObjectStorageService storage;
    private final IndexerProperties.Thumbnail cfg;

    public ThumbnailStage(ObjectStorageService storage, IndexerProperties properties) {
        this.storage = storage;
        this.cfg = properties.pipeline().thumbnail();
    }

    @Override
    public String name() {
        return "thumbnail";
    }

    @Override
    public void process(ProcessingContext ctx) throws Exception {
        BufferedImage source = ctx.image();
        BufferedImage thumb = source.getWidth() <= cfg.maxSize() && source.getHeight() <= cfg.maxSize()
                ? source
                : Thumbnails.of(source).size(cfg.maxSize(), cfg.maxSize()).asBufferedImage();
        byte[] jpeg = ImageCodec.encode(thumb, "jpeg", cfg.quality());
        String key = "derivatives/" + ctx.document().getAssetId() + "/thumbnail.jpg";
        storage.put(key, jpeg, "image/jpeg");
        ctx.document().setThumbnailKey(key);
    }
}
