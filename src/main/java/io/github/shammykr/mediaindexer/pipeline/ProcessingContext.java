package io.github.shammykr.mediaindexer.pipeline;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.ingest.AssetEvent;
import net.coobird.thumbnailator.Thumbnails;

/** Per-asset state handed from stage to stage. Decoded images are cached so each stage doesn't re-decode. */
public class ProcessingContext {

    private static final int MODEL_INPUT_SIZE = 224;

    private final AssetEvent event;
    private final byte[] original;
    private final AssetDocument document;
    private BufferedImage image;
    private byte[] modelInput;

    public ProcessingContext(AssetEvent event, byte[] original, AssetDocument document) {
        this.event = event;
        this.original = original;
        this.document = document;
    }

    public AssetEvent event() {
        return event;
    }

    public byte[] original() {
        return original;
    }

    public AssetDocument document() {
        return document;
    }

    public BufferedImage image() throws IOException {
        if (image == null) {
            try {
                image = ImageIO.read(new ByteArrayInputStream(original));
            } catch (IOException e) {
                // A corrupt file will fail the same way on every attempt.
                throw new NonRetryableProcessingException("corrupt image: " + e.getMessage(), e);
            }
            if (image == null) {
                throw new NonRetryableProcessingException("cannot decode image (" + document.getContentType() + ")");
            }
        }
        return image;
    }

    /** A 224px JPEG, the typical input size for an image model; keeps inference payloads small. */
    public byte[] modelInput() throws IOException {
        if (modelInput == null) {
            BufferedImage scaled = Thumbnails.of(image()).size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE).asBufferedImage();
            modelInput = ImageCodec.encode(scaled, "jpeg", 0.9f);
        }
        return modelInput;
    }
}
