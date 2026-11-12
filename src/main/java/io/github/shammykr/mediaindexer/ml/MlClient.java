package io.github.shammykr.mediaindexer.ml;

import java.util.Base64;
import java.util.List;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.github.shammykr.mediaindexer.ml.MlApi.ClassifyRequest;
import io.github.shammykr.mediaindexer.ml.MlApi.ClassifyResponse;
import io.github.shammykr.mediaindexer.ml.MlApi.EmbedRequest;
import io.github.shammykr.mediaindexer.ml.MlApi.EmbedResponse;
import io.github.shammykr.mediaindexer.ml.MlApi.Prediction;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for the inference endpoints used by the auto-tagging and embedding stages and by search. */
@Component
public class MlClient {

    private final RestClient http;
    private final int expectedDims;

    public MlClient(RestClient.Builder builder, IndexerProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(properties.ml().timeout());
        this.http = builder.baseUrl(properties.ml().baseUrl()).requestFactory(factory).build();
        this.expectedDims = properties.index().embeddingDims();
    }

    public List<Prediction> classify(byte[] image) {
        ClassifyResponse response = http.post().uri("/ml/classify")
                .body(new ClassifyRequest(Base64.getEncoder().encodeToString(image)))
                .retrieve()
                .body(ClassifyResponse.class);
        return response == null || response.labels() == null ? List.of() : response.labels();
    }

    public float[] embedImage(byte[] image, String text) {
        return embed(new EmbedRequest(Base64.getEncoder().encodeToString(image), text));
    }

    public float[] embedText(String text) {
        return embed(new EmbedRequest(null, text));
    }

    private float[] embed(EmbedRequest request) {
        EmbedResponse response = http.post().uri("/ml/embed").body(request).retrieve().body(EmbedResponse.class);
        if (response == null || response.vector() == null || response.vector().length != expectedDims) {
            throw new IllegalStateException("embedding endpoint returned an unexpected vector size");
        }
        return response.vector();
    }
}
