package io.github.shammykr.mediaindexer.pipeline.stages;

import java.util.List;
import java.util.Locale;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.pipeline.ImageCodec;
import io.github.shammykr.mediaindexer.pipeline.ProcessingContext;
import io.github.shammykr.mediaindexer.pipeline.ProcessingStage;
import io.github.shammykr.mediaindexer.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts images in a configured set of source formats (default PNG/BMP/GIF) to a delivery format
 * (default JPEG) and stores the rendition next to the original. Other formats pass through untouched.
 */
@Component
public class FormatConversionStage implements ProcessingStage {

    private static final Logger log = LoggerFactory.getLogger(FormatConversionStage.class);

    private final ObjectStorageService storage;
    private final List<String> sourceFormats;
    private final String targetFormat;
    private final float quality;

    public FormatConversionStage(ObjectStorageService storage, IndexerProperties properties) {
        IndexerProperties.Conversion cfg = properties.pipeline().conversion();
        this.storage = storage;
        this.sourceFormats = cfg.sourceFormats().stream().map(f -> f.toLowerCase(Locale.ROOT)).toList();
        this.targetFormat = cfg.targetFormat().toLowerCase(Locale.ROOT);
        this.quality = cfg.quality();
    }

    @Override
    public String name() {
        return "format-conversion";
    }

    @Override
    public void process(ProcessingContext ctx) throws Exception {
        AssetDocument doc = ctx.document();
        if (!sourceFormats.contains(doc.getFileType())) {
            log.debug("No conversion needed for fileType={}", doc.getFileType());
            return;
        }
        byte[] converted = ImageCodec.encode(ctx.image(), targetFormat, quality);
        String extension = targetFormat.equals("jpeg") ? "jpg" : targetFormat;
        String key = "derivatives/" + doc.getAssetId() + "/converted." + extension;
        storage.put(key, converted, ImageCodec.mimeType(targetFormat));
        doc.setConvertedKey(key);
        doc.setConvertedFormat(targetFormat);
        log.info("Converted {} -> {} ({} -> {} bytes)", doc.getFileType(), targetFormat, ctx.original().length,
                converted.length);
    }
}
