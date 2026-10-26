package dev.irisvisualizer.editor;

import java.io.*;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Read-only first-frame PNG/JPEG decoder with bounds checked before raster allocation. */
public final class AssetImage {
    public static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;
    public static final int MAX_EDGE = 4096;
    public static final long MAX_PIXELS = 4_194_304;
    public record Decoded(int width, int height, byte[] rgba) {
        public Decoded { rgba = rgba.clone(); }
        @Override public byte[] rgba() { return rgba.clone(); }
    }
    public static boolean supportedPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }
    public static void checkSize(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE || (long)width * height > MAX_PIXELS)
            throw new IOException("Asset image exceeds 4096 pixels per edge or 4 megapixels");
    }
    public static Decoded decode(byte[] encoded) throws IOException {
        if (encoded.length > MAX_ENCODED_BYTES) throw new IOException("Asset image exceeds 16 MiB encoded limit");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported or invalid image data");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw new IOException("Only PNG and JPEG assets are supported");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0); checkSize(width, height);
                var image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) throw new IOException("Invalid image dimensions");
                byte[] pixels = new byte[width * height * 4]; int position = 0;
                for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                    int argb = image.getRGB(x, y);
                    pixels[position++] = (byte)(argb >> 16); pixels[position++] = (byte)(argb >> 8);
                    pixels[position++] = (byte)argb; pixels[position++] = (byte)(argb >>> 24);
                }
                return new Decoded(width, height, pixels);
            } finally { reader.dispose(); }
        }
    }
}
