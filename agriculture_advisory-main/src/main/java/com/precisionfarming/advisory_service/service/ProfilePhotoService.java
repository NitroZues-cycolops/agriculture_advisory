package com.precisionfarming.advisory_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;

/**
 * PART 2a: turns an uploaded profile picture into the 256x256 JPG that the
 * avatar endpoint serves.
 *
 * WHY THIS IS STRICT
 *   - JPG or PNG ONLY, decided by the file's MAGIC BYTES - not by the extension
 *     and not by the browser's Content-Type, both of which can lie. A text file
 *     renamed ".png" is therefore refused.
 *   - 2 MB on the wire.
 *   - The width and height are read with an ImageReader BEFORE the pixels are
 *     decoded; a tiny file that claims a huge size is refused cheaply instead of
 *     exhausting memory.
 *   - The image is drawn onto a WHITE background (so a transparent PNG does not
 *     turn black in the JPEG), cropped to a centred square and resized to 256.
 *   - The result is stored OUTSIDE src/main/resources/static, under
 *     <uploads>/avatars, and served back through AvatarController.
 *
 * Every failure is returned as a message key - nothing throws out of store(),
 * so a bad upload can never become a 500.
 */
@Service
public class ProfilePhotoService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    /** Same 2 MB cap as the community photo upload. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    /** The stored avatar is always a square of this many pixels. */
    public static final int SIZE = 256;

    /** Refuse to decode an image above either of these limits. */
    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_SIDE = 8_000;

    private final String uploadDir;

    public ProfilePhotoService(@Value("${pfas.uploads.dir:uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    /** Either the stored absolute path (errorKey == null) or a message key. */
    public record StoreResult(String path, String errorKey) {
        private static StoreResult ok(String path) { return new StoreResult(path, null); }
        private static StoreResult error(String key) { return new StoreResult(null, key); }
    }

    /** Validates, crops, resizes and stores the upload; never throws. */
    public StoreResult store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return StoreResult.error("error.required");
        }
        if (file.getSize() > MAX_BYTES) {
            return StoreResult.error("error.image.size");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.info("[PROFILE] could not read the uploaded photo: {}", e.getMessage());
            return corrupt();
        }
        if (!isJpeg(bytes) && !isPng(bytes)) {
            return StoreResult.error("error.image.type");
        }
        return process(bytes, userId);
    }

    /** Removes a stored avatar file, but only inside our own avatars folder. */
    public void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            File dir = avatarsDir();
            File file = new File(path).getAbsoluteFile();
            File parent = file.getParentFile();
            if (parent != null && parent.getAbsolutePath().equals(dir.getAbsolutePath())
                    && file.exists() && !file.delete()) {
                log.warn("[PROFILE] could not delete the old avatar file {}", path);
            }
        } catch (RuntimeException e) {
            log.warn("[PROFILE] could not delete the old avatar file {}: {}", path, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private StoreResult process(byte[] bytes, Long userId) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return corrupt();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return corrupt();
            }
            ImageReader reader = readers.next();
            int width;
            int height;
            BufferedImage source;
            try {
                reader.setInput(in, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return corrupt();
                }
                // Dimensions are known without decoding, so an oversized image is
                // refused before a single pixel is read.
                if (width > MAX_SIDE || height > MAX_SIDE
                        || (long) width * (long) height > MAX_PIXELS) {
                    return StoreResult.error("error.image.dimensions");
                }
                source = reader.read(0);
            } catch (IOException | RuntimeException e) {
                log.info("[PROFILE] avatar for user {} could not be read: {}", userId, e.getMessage());
                return corrupt();
            } finally {
                reader.dispose();
            }
            if (source == null) {
                return corrupt();
            }
            BufferedImage square = toSquare(source, width, height);
            try {
                return StoreResult.ok(writeJpeg(square, userId));
            } catch (IOException e) {
                log.error("[PROFILE] could not save the avatar for user {}: {}", userId, e.getMessage());
                return StoreResult.error("error.image.save");
            }
        } catch (IOException e) {
            log.info("[PROFILE] avatar for user {} could not be processed: {}", userId, e.getMessage());
            return corrupt();
        }
    }

    /** Centre-crops to a square on a white canvas and scales it to SIZE. */
    private BufferedImage toSquare(BufferedImage source, int width, int height) {
        int side = Math.min(width, height);
        int x = (width - side) / 2;
        int y = (height - side) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            // A JPEG has no alpha channel, so a transparent PNG would otherwise
            // flatten to black. Fill white first.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SIZE, SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private String writeJpeg(BufferedImage image, Long userId) throws IOException {
        File dir = avatarsDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create the avatar upload folder: " + dir.getAbsolutePath());
        }
        String fileName = "avatar-" + userId + "-"
                + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        File target = new File(dir, fileName).getAbsoluteFile();
        if (!ImageIO.write(image, "jpg", target)) {
            throw new IOException("No JPEG writer is available");
        }
        return target.getAbsolutePath();
    }

    private File avatarsDir() {
        // getAbsoluteFile() matters: a RELATIVE path would be resolved against
        // the servlet container's temp dir, exactly like the community upload.
        return new File(uploadDir, "avatars").getAbsoluteFile();
    }

    private static boolean isJpeg(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 4 && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47;
    }

    private static StoreResult corrupt() {
        return StoreResult.error("error.image.corrupt");
    }
}
