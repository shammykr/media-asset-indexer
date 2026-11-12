package io.github.shammykr.mediaindexer.pipeline;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Image encoding helpers shared by the thumbnail, conversion and ML stages. */
public final class ImageCodec {

    private ImageCodec() {}

    /** Flattens transparency onto white so the image can be written as JPEG. */
    public static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rgb;
    }

    public static byte[] encode(BufferedImage image, String format, float quality) throws IOException {
        String fmt = format.equalsIgnoreCase("jpg") ? "jpeg" : format.toLowerCase();
        BufferedImage toWrite = fmt.equals("jpeg") ? toRgb(image) : image;
        ImageWriter writer = ImageIO.getImageWritersByFormatName(fmt).next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                if (param.getCompressionTypes() != null && param.getCompressionType() == null) {
                    param.setCompressionType(param.getCompressionTypes()[0]);
                }
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(toWrite, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    public static String mimeType(String format) {
        String fmt = format.equalsIgnoreCase("jpg") ? "jpeg" : format.toLowerCase();
        return "image/" + fmt;
    }
}
