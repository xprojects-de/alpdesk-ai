package de.alpdesk.services.document;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Images for a model to look at - the chat's files (services.chat.ChatFileReader) and the company's documents
 * ({@link ScanReader}): an image or a PDF page as a JPEG of at most {@link #MAX_EDGE} pixels, and a PDF's text page by
 * page, to tell the pages with text from the scanned ones.
 */
public final class DocumentImages {

    /** The longest edge of an image sent to a model: larger ones only cost tokens (the providers scale them down). */
    public static final int MAX_EDGE = 2048;
    /** The image types Java reads without a plugin. WebP and HEIC would need one - not supported. */
    public static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/bmp");
    /** Their file name extensions - a knowledge source reads images only when the tenant's image model reads them. */
    public static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final float RENDER_DPI = 120;
    /**
     * The most pixels an image may declare - above any camera's. A few kilobytes of PNG may declare 40,000 x 40,000
     * pixels, gigabytes once decoded: what the server's memory would not survive is refused before it is decoded.
     */
    public static final long MAX_PIXELS = 200_000_000L;
    /**
     * The most pixels an image in a PDF may declare - a page of A4 scanned at 600 dpi has 35 million. PDFBox decodes it
     * at its full size (about 7 bytes a pixel), whatever size it is drawn at.
     */
    private static final long MAX_PDF_IMAGE_PIXELS = 40_000_000L;
    private static final float JPEG_QUALITY = 0.85f;

    private DocumentImages() {
    }

    /** The image as a JPEG for a model. 400 if it cannot be read or declares more than {@link #MAX_PIXELS}. */
    public static byte[] imageAsJpeg(byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = input == null ? Collections.emptyIterator() : ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw ServiceException.badRequest("image.unreadable", "The image could not be read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return toJpeg(decodedSmall(reader));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ServiceException refused) {
                throw refused;
            }
            throw ServiceException.badRequest("image.unreadable", "The image could not be read");
        }
    }

    /** The image's width and height as its header declares them - null when Java reads no image in it. */
    public static @Nullable Dimension size(byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = input == null ? Collections.emptyIterator() : ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Decoded at about the size a model gets ({@link #MAX_EDGE}): every n-th pixel of a large photo only - its full
     * size is never held in memory.
     */
    private static BufferedImage decodedSmall(ImageReader reader) throws IOException {
        long width = reader.getWidth(0);
        long height = reader.getHeight(0);
        refuseLargerThan(MAX_PIXELS, width, height);
        int step = (int) Math.max(1, Math.max(width, height) / MAX_EDGE);
        ImageReadParam parameters = reader.getDefaultReadParam();
        parameters.setSourceSubsampling(step, step, 0, 0);
        return reader.read(0, parameters);
    }

    private static void refuseLargerThan(long maxPixels, long width, long height) {
        if (width * height > maxPixels) {
            throw ServiceException.badRequest("image.tooLarge", "The image is too large - at most "
                    + maxPixels / 1_000_000 + " megapixels",
                    Map.of("maxMegapixels", String.valueOf(maxPixels / 1_000_000)));
        }
    }

    /** The text of each page of the PDF, in order. 400 if it cannot be read. */
    public static List<String> pageTexts(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document).strip());
            }
            return pages;
        } catch (IOException | RuntimeException e) {
            // PDFBox throws runtime exceptions on broken page content too - like imageAsJpeg: refused, not a 500 that
            // ends a whole archive's import.
            throw ServiceException.badRequest("pdf.unreadable", "The PDF could not be read");
        }
    }

    /**
     * Pages of a PDF (1-based, inclusive) as JPEGs; pages beyond the last are left out. 400 if it cannot be read, holds
     * an image larger than {@link #MAX_PDF_IMAGE_PIXELS} or a page cannot be drawn.
     */
    public static RenderedPages renderPages(byte[] pdf, int firstPage, int lastPage) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            PDFRenderer renderer = new ImageLimitingRenderer(document);
            List<byte[]> images = new ArrayList<>();
            for (int page = Math.max(1, firstPage); page <= Math.min(lastPage, pages); page++) {
                images.add(toJpeg(renderer.renderImageWithDPI(page - 1, dpi(document.getPage(page - 1)), ImageType.RGB)));
            }
            return new RenderedPages(pages, images);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ServiceException refused) {
                throw refused;
            }
            // PDFBox draws a broken colour space or image with a runtime exception, not an IOException - refused like
            // an unreadable PDF, so ScanReader keeps the page's text instead of failing the whole document.
            throw ServiceException.badRequest("pdf.unreadable", "The PDF could not be read");
        }
    }

    /**
     * 120 dpi, less for a page so large its longest edge would pass MAX_EDGE: a page may declare any size, and is
     * rendered in memory whole.
     */
    private static float dpi(PDPage page) {
        PDRectangle box = page.getCropBox();
        float longestEdgeInches = Math.max(box.getWidth(), box.getHeight()) / 72f;
        return longestEdgeInches <= 0 ? RENDER_DPI : Math.min(RENDER_DPI, MAX_EDGE / longestEdgeInches);
    }

    /** Refuses an image (or its mask) above MAX_PDF_IMAGE_PIXELS before PDFBox decodes it. */
    private static final class ImageLimitingRenderer extends PDFRenderer {

        ImageLimitingRenderer(PDDocument document) {
            super(document);
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                @Override
                public void drawImage(PDImage image) throws IOException {
                    refuseLarge(image);
                    if (image instanceof PDImageXObject xObject) {
                        refuseLarge(xObject.getSoftMask());
                        refuseLarge(xObject.getMask());
                    }
                    super.drawImage(image);
                }
            };
        }

        private static void refuseLarge(PDImage image) {
            if (image != null) {
                refuseLargerThan(MAX_PDF_IMAGE_PIXELS, image.getWidth(), image.getHeight());
            }
        }
    }

    /** Scaled down to MAX_EDGE, transparency on white (JPEG has none). */
    public static byte[] toJpeg(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(target, null, null), parameters);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the image", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** The number of pages of the PDF and the rendered ones. */
    public record RenderedPages(int pageCount, List<byte[]> images) {
    }
}
