package io.github.shammykr.mediaindexer.ml;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.github.shammykr.mediaindexer.ml.MlApi.Prediction;
import org.springframework.stereotype.Component;

/**
 * A deterministic, dependency-free stand-in for an image classifier and an embedding model.
 *
 * <p>Classification looks at real pixel statistics (hue distribution, brightness, saturation,
 * edge density, region colors) and emits labels such as {@code blue}, {@code sky}, {@code vegetation},
 * {@code dark}, {@code landscape}. Embeddings are built from two halves:
 * <ul>
 *   <li>a visual half: a 4x4x4 RGB color histogram (Hellinger-normalised), so images with similar
 *       color composition land close together;</li>
 *   <li>a text half: signed feature hashing of tokens (tags, title, document text), so a text query
 *       can be embedded into the same space and matched against assets by kNN.</li>
 * </ul>
 * The output is consistent for identical input, which is what the pipeline needs to exercise
 * dense_vector indexing and kNN search end to end.
 */
@Component
public class MockVisionModel {

    public static final String MODEL_NAME = "mock-histogram-hash-v1";

    private static final int VISUAL_DIMS = 64;
    private static final int SAMPLE = 64;
    private static final float VISUAL_WEIGHT = 0.8f;
    private static final float TEXT_WEIGHT = 0.6f;
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of",
            "on", "or", "that", "the", "this", "to", "was", "with", "img", "jpg", "jpeg", "png", "dsc");

    private final int dims;

    public MockVisionModel(IndexerProperties properties) {
        this.dims = properties.index().embeddingDims();
        if (dims <= VISUAL_DIMS) {
            throw new IllegalArgumentException("embedding-dims must be greater than " + VISUAL_DIMS);
        }
    }

    public int dims() {
        return dims;
    }

    // ---------------------------------------------------------------- classification

    public List<Prediction> classify(BufferedImage source) {
        BufferedImage img = resample(source);
        int n = SAMPLE * SAMPLE;
        String[] colorOf = new String[n];
        double sumV = 0, sumS = 0, edges = 0;
        double topBlue = 0, topWarm = 0, bottomDarkV = 0;
        float[] hsv = new float[3];
        float[] lum = new float[n];

        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                Color.RGBtoHSB(r, g, b, hsv);
                int i = y * SAMPLE + x;
                colorOf[i] = colorName(hsv[0], hsv[1], hsv[2]);
                lum[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                sumV += hsv[2];
                sumS += hsv[1];
                if (y < SAMPLE / 3 && (colorOf[i].equals("blue") || colorOf[i].equals("cyan"))) {
                    topBlue++;
                }
                if (y < SAMPLE / 2 && Set.of("orange", "red", "pink", "yellow").contains(colorOf[i])) {
                    topWarm++;
                }
                if (y >= SAMPLE / 2) {
                    bottomDarkV += hsv[2];
                }
            }
        }
        for (int y = 1; y < SAMPLE; y++) {
            for (int x = 1; x < SAMPLE; x++) {
                int i = y * SAMPLE + x;
                float gradient = Math.abs(lum[i] - lum[i - 1]) + Math.abs(lum[i] - lum[i - SAMPLE]);
                if (gradient > 0.12f) {
                    edges++;
                }
            }
        }

        Map<String, Integer> colorCounts = new LinkedHashMap<>();
        for (String c : colorOf) {
            colorCounts.merge(c, 1, Integer::sum);
        }

        double meanV = sumV / n;
        double meanS = sumS / n;
        double edgeDensity = edges / ((SAMPLE - 1.0) * (SAMPLE - 1.0));
        double half = n / 2.0;

        List<Prediction> out = new ArrayList<>();
        colorCounts.forEach((color, count) -> {
            double fraction = (double) count / n;
            if (fraction >= 0.12) {
                out.add(new Prediction(color, round(Math.min(1.0, fraction * 1.5))));
            }
        });

        if (meanV > 0.65) out.add(new Prediction("bright", round((meanV - 0.5) * 2)));
        if (meanV < 0.35) out.add(new Prediction("dark", round((0.5 - meanV) * 2)));
        if (meanV < 0.18) out.add(new Prediction("night", round(1 - meanV * 3)));
        if (meanS > 0.45) out.add(new Prediction("colorful", round(Math.min(1, meanS * 1.3))));
        if (meanS < 0.12) out.add(new Prediction("monochrome", round(1 - meanS * 4)));
        if (edgeDensity > 0.18) out.add(new Prediction("detailed", round(Math.min(1, edgeDensity * 2.5))));
        if (edgeDensity < 0.04) out.add(new Prediction("minimal", round(1 - edgeDensity * 10)));

        double topThird = n / 3.0;
        if (topBlue / topThird > 0.4) out.add(new Prediction("sky", round(topBlue / topThird)));
        double green = colorCounts.getOrDefault("green", 0) / (double) n;
        if (green > 0.25) out.add(new Prediction("vegetation", round(Math.min(1, green * 1.6))));
        if (topWarm / half > 0.3 && bottomDarkV / half < 0.45) out.add(new Prediction("sunset", round(topWarm / half)));
        double white = colorCounts.getOrDefault("white", 0) / (double) n;
        if (white > 0.5 && meanS < 0.15) out.add(new Prediction("snow-or-paper", round(white)));
        if (white > 0.6 && edgeDensity > 0.03 && colorCounts.size() <= 3) out.add(new Prediction("graphic", round(white)));

        double aspect = (double) source.getWidth() / source.getHeight();
        if (aspect > 1.15) out.add(new Prediction("landscape", 1.0));
        else if (aspect < 0.87) out.add(new Prediction("portrait", 1.0));
        else out.add(new Prediction("square", 1.0));

        out.sort(Comparator.comparingDouble(Prediction::confidence).reversed());
        return out;
    }

    // ---------------------------------------------------------------- embeddings

    public float[] embed(BufferedImage image, String text) {
        float[] vector = new float[dims];
        if (image != null) {
            float[] visual = colorHistogram(resample(image));
            for (int i = 0; i < VISUAL_DIMS; i++) {
                vector[i] = VISUAL_WEIGHT * visual[i];
            }
        }
        if (text != null && !text.isBlank() || image == null) {
            float[] textual = hashText(text == null ? "" : text, dims - VISUAL_DIMS);
            for (int i = 0; i < textual.length; i++) {
                vector[VISUAL_DIMS + i] = TEXT_WEIGHT * textual[i];
            }
        }
        return normalize(vector);
    }

    private float[] colorHistogram(BufferedImage img) {
        float[] hist = new float[VISUAL_DIMS];
        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                int rgb = img.getRGB(x, y);
                int r = ((rgb >> 16) & 0xff) >> 6, g = ((rgb >> 8) & 0xff) >> 6, b = (rgb & 0xff) >> 6;
                hist[(r << 4) | (g << 2) | b]++;
            }
        }
        for (int i = 0; i < hist.length; i++) {
            hist[i] = (float) Math.sqrt(hist[i] / (SAMPLE * SAMPLE));
        }
        return normalize(hist);
    }

    private float[] hashText(String text, int size) {
        float[] v = new float[size];
        Map<String, Integer> tf = new LinkedHashMap<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && !STOPWORDS.contains(token)) {
                tf.merge(stem(token), 1, Integer::sum);
            }
        }
        if (tf.isEmpty()) {
            tf.put("__empty__", 1); // cosine similarity rejects zero vectors
        }
        tf.forEach((token, count) -> {
            long h = hash(token);
            int idx = (int) (h % size);
            float sign = ((h >> 20) & 1) == 0 ? 1f : -1f;
            v[idx] += sign * (float) (1 + Math.log(count));
        });
        return normalize(v);
    }

    /** Very small suffix stripper so "trees" and "tree" share a bucket. */
    private static String stem(String token) {
        if (token.length() > 4 && token.endsWith("ies")) return token.substring(0, token.length() - 3) + "y";
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")) return token.substring(0, token.length() - 1);
        return token;
    }

    private static long hash(String token) {
        CRC32 crc = new CRC32();
        crc.update(token.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    // ---------------------------------------------------------------- helpers

    private static BufferedImage resample(BufferedImage source) {
        BufferedImage out = new BufferedImage(SAMPLE, SAMPLE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SAMPLE, SAMPLE);
        g.drawImage(source, 0, 0, SAMPLE, SAMPLE, null);
        g.dispose();
        return out;
    }

    private static String colorName(float h, float s, float v) {
        if (v < 0.18f) return "black";
        if (s < 0.15f) return v > 0.8f ? "white" : "gray";
        float deg = h * 360f;
        if (deg < 15 || deg >= 345) return "red";
        if (deg < 40) return v < 0.6f && s > 0.4f ? "brown" : "orange";
        if (deg < 70) return "yellow";
        if (deg < 165) return "green";
        if (deg < 195) return "cyan";
        if (deg < 260) return "blue";
        if (deg < 300) return "purple";
        return "pink";
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        return v;
    }

    private static double round(double value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 1000) / 1000.0;
    }
}
