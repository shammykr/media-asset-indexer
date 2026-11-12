package io.github.shammykr.mediaindexer.pipeline;

import java.util.Locale;
import java.util.Map;

/** Content-type sniffing (magic bytes first, file extension second) and category assignment. */
public final class MediaTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"), Map.entry("bmp", "image/bmp"), Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"), Map.entry("webp", "image/webp"), Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"), Map.entry("md", "text/markdown"), Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"));

    private static final Map<String, String> FILE_TYPE = Map.ofEntries(
            Map.entry("image/jpeg", "jpeg"), Map.entry("image/png", "png"), Map.entry("image/gif", "gif"),
            Map.entry("image/bmp", "bmp"), Map.entry("image/tiff", "tiff"), Map.entry("image/webp", "webp"),
            Map.entry("application/pdf", "pdf"), Map.entry("text/plain", "txt"), Map.entry("text/markdown", "md"),
            Map.entry("text/csv", "csv"), Map.entry("application/json", "json"));

    private MediaTypes() {}

    public static String detectContentType(byte[] data, String filename, String declared) {
        String sniffed = sniff(data);
        if (sniffed != null) {
            return sniffed;
        }
        String byExt = BY_EXTENSION.get(extension(filename));
        if (byExt != null) {
            return byExt;
        }
        return declared == null || declared.isBlank() ? "application/octet-stream" : declared;
    }

    public static String fileType(String contentType) {
        return FILE_TYPE.getOrDefault(contentType, "other");
    }

    public static String category(String contentType) {
        if (contentType.startsWith("image/")) return "image";
        if (contentType.equals("application/pdf") || contentType.startsWith("text/")
                || contentType.equals("application/json")) return "document";
        return "other";
    }

    public static boolean isText(String contentType) {
        return contentType.startsWith("text/") || contentType.equals("application/json");
    }

    public static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sniff(byte[] d) {
        if (d.length < 12) return null;
        if (u(d[0]) == 0xFF && u(d[1]) == 0xD8 && u(d[2]) == 0xFF) return "image/jpeg";
        if (u(d[0]) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return "image/gif";
        if (d[0] == 'B' && d[1] == 'M') return "image/bmp";
        if ((d[0] == 'I' && d[1] == 'I' && d[2] == 42) || (d[0] == 'M' && d[1] == 'M' && d[3] == 42)) return "image/tiff";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B') return "image/webp";
        if (d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') return "application/pdf";
        return null;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
