package io.github.shammykr.mediaindexer.ml;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

import io.github.shammykr.mediaindexer.ml.MlApi.ClassifyRequest;
import io.github.shammykr.mediaindexer.ml.MlApi.ClassifyResponse;
import io.github.shammykr.mediaindexer.ml.MlApi.EmbedRequest;
import io.github.shammykr.mediaindexer.ml.MlApi.EmbedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mock inference server. It is deliberately exposed over HTTP (rather than called in-process) so the
 * pipeline treats ML as an external dependency: point {@code indexer.ml.base-url} at a real model
 * server that speaks the same JSON and nothing else changes.
 */
@RestController
@RequestMapping("/ml")
public class MockModelController {

    private final MockVisionModel model;

    public MockModelController(MockVisionModel model) {
        this.model = model;
    }

    @PostMapping("/classify")
    public ClassifyResponse classify(@RequestBody ClassifyRequest request) {
        BufferedImage image = decode(request.image());
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image is required");
        }
        return new ClassifyResponse(MockVisionModel.MODEL_NAME, model.classify(image));
    }

    @PostMapping("/embed")
    public EmbedResponse embed(@RequestBody EmbedRequest request) {
        float[] vector = model.embed(decode(request.image()), request.text());
        return new EmbedResponse(MockVisionModel.MODEL_NAME, model.dims(), vector);
    }

    private static BufferedImage decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported image format");
            }
            return image;
        } catch (IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "could not decode image", e);
        }
    }
}
