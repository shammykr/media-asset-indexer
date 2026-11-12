package io.github.shammykr.mediaindexer.ml;

import java.util.List;

/** Wire format of the inference endpoints. Images travel as base64 so a real model server can be dropped in. */
public final class MlApi {

    private MlApi() {}

    public record ClassifyRequest(String image) {}

    public record ClassifyResponse(String model, List<Prediction> labels) {}

    public record Prediction(String label, double confidence) {}

    /** Either field may be null; an image request may carry text (e.g. tags) to blend into the vector. */
    public record EmbedRequest(String image, String text) {}

    public record EmbedResponse(String model, int dims, float[] vector) {}
}
