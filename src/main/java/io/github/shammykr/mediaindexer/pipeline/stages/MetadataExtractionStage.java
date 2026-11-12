package io.github.shammykr.mediaindexer.pipeline.stages;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import io.github.shammykr.mediaindexer.config.IndexerProperties;
import io.github.shammykr.mediaindexer.index.AssetDocument;
import io.github.shammykr.mediaindexer.pipeline.MediaTypes;
import io.github.shammykr.mediaindexer.pipeline.NonRetryableProcessingException;
import io.github.shammykr.mediaindexer.pipeline.ProcessingContext;
import io.github.shammykr.mediaindexer.pipeline.ProcessingStage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Extracts dimensions and EXIF from images, and page count / text / info from documents. */
@Component
public class MetadataExtractionStage implements ProcessingStage {

    private static final Logger log = LoggerFactory.getLogger(MetadataExtractionStage.class);

    private final int maxContentChars;

    public MetadataExtractionStage(IndexerProperties properties) {
        this.maxContentChars = properties.pipeline().document().maxContentChars();
    }

    @Override
    public String name() {
        return "metadata-extraction";
    }

    @Override
    public void process(ProcessingContext ctx) throws Exception {
        AssetDocument doc = ctx.document();
        switch (doc.getCategory()) {
            case "image" -> extractImage(ctx, doc);
            case "document" -> extractDocument(ctx, doc);
            default -> { /* size and content type are already set by the executor */ }
        }
        if (doc.getTitle() == null || doc.getTitle().isBlank()) {
            String name = doc.getFilename();
            int dot = name.lastIndexOf('.');
            doc.setTitle((dot > 0 ? name.substring(0, dot) : name).replaceAll("[_\\-]+", " ").trim());
        }
    }

    private void extractImage(ProcessingContext ctx, AssetDocument doc) throws Exception {
        BufferedImage image = ctx.image();
        doc.setWidth(image.getWidth());
        doc.setHeight(image.getHeight());

        Map<String, String> exif = new LinkedHashMap<>();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(ctx.original()));
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            put(exif, "make", ifd0, ExifIFD0Directory.TAG_MAKE);
            put(exif, "model", ifd0, ExifIFD0Directory.TAG_MODEL);
            put(exif, "orientation", ifd0, ExifIFD0Directory.TAG_ORIENTATION);
            put(exif, "software", ifd0, ExifIFD0Directory.TAG_SOFTWARE);

            ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            put(exif, "exposureTime", sub, ExifSubIFDDirectory.TAG_EXPOSURE_TIME);
            put(exif, "fNumber", sub, ExifSubIFDDirectory.TAG_FNUMBER);
            put(exif, "iso", sub, ExifSubIFDDirectory.TAG_ISO_EQUIVALENT);
            put(exif, "focalLength", sub, ExifSubIFDDirectory.TAG_FOCAL_LENGTH);
            put(exif, "lensModel", sub, ExifSubIFDDirectory.TAG_LENS_MODEL);
            if (sub != null && sub.getDateOriginal() != null) {
                doc.setCapturedAt(sub.getDateOriginal().toInstant());
                exif.put("dateTimeOriginal", doc.getCapturedAt().toString());
            }

            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            GeoLocation location = gps == null ? null : gps.getGeoLocation();
            if (location != null && !location.isZero()) {
                exif.put("gpsLatitude", Double.toString(location.getLatitude()));
                exif.put("gpsLongitude", Double.toString(location.getLongitude()));
            }
        } catch (Exception e) {
            // Missing or malformed EXIF should not fail the asset.
            log.debug("No readable EXIF for {}: {}", doc.getAssetId(), e.toString());
        }
        doc.setExif(exif);
    }

    private void extractDocument(ProcessingContext ctx, AssetDocument doc) throws Exception {
        if (doc.getContentType().equals("application/pdf")) {
            try (PDDocument pdf = Loader.loadPDF(ctx.original())) {
                doc.setPageCount(pdf.getNumberOfPages());
                PDDocumentInformation info = pdf.getDocumentInformation();
                if (info != null) {
                    if (doc.getTitle() == null && info.getTitle() != null && !info.getTitle().isBlank()) {
                        doc.setTitle(info.getTitle().trim());
                    }
                    Map<String, String> meta = new LinkedHashMap<>();
                    if (info.getAuthor() != null) meta.put("author", info.getAuthor());
                    if (info.getCreator() != null) meta.put("creator", info.getCreator());
                    if (info.getProducer() != null) meta.put("producer", info.getProducer());
                    if (info.getCreationDate() != null) {
                        doc.setCapturedAt(info.getCreationDate().toInstant());
                    }
                    doc.setExif(meta);
                }
                doc.setContent(truncate(new PDFTextStripper().getText(pdf)));
            } catch (java.io.IOException e) {
                throw new NonRetryableProcessingException("unreadable PDF", e);
            }
        } else if (MediaTypes.isText(doc.getContentType())) {
            doc.setContent(truncate(new String(ctx.original(), StandardCharsets.UTF_8)));
        }
    }

    private String truncate(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > maxContentChars ? normalized.substring(0, maxContentChars) : normalized;
    }

    private static void put(Map<String, String> out, String key, Directory dir, int tag) {
        if (dir != null && dir.containsTag(tag)) {
            String value = dir.getDescription(tag);
            if (value != null && !value.isBlank()) {
                out.put(key, value.trim());
            }
        }
    }
}
