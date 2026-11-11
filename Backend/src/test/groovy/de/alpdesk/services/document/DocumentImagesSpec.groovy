package de.alpdesk.services.document

import de.alpdesk.services.common.ServiceException
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import spock.lang.Specification

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Images and PDF pages for a model: never held in memory at their full size - an image or a page may declare any size
 * in a few kilobytes.
 */
class DocumentImagesSpec extends Specification {

    def "an image declaring more pixels than any camera is refused before it is decoded"() {
        when: "a PNG of a few bytes that says 40,000 x 40,000 pixels - gigabytes once decoded"
        DocumentImages.imageAsJpeg(pngDeclaring(40_000, 40_000))

        then:
        def e = thrown(ServiceException)
        e.message == "The image is too large - at most 200 megapixels"
    }

    def "a large image is decoded at about the size a model gets"() {
        when:
        def jpeg = ImageIO.read(new ByteArrayInputStream(DocumentImages.imageAsJpeg(png(4100, 100))))

        then:
        jpeg.width == DocumentImages.MAX_EDGE
    }

    def "a page of any declared size is rendered at most MAX_EDGE pixels long"() {
        given: "a page of 200 x 200 inches - 24,000 pixels square at 120 dpi"
        def pdf = new ByteArrayOutputStream()
        new PDDocument().withCloseable { document ->
            document.addPage(new PDPage(new PDRectangle(14_400, 14_400)))
            document.save(pdf)
        }

        when:
        def page = ImageIO.read(new ByteArrayInputStream(DocumentImages.renderPages(pdf.toByteArray(), 1, 1).images().first()))

        then:
        Math.max(page.width, page.height) <= DocumentImages.MAX_EDGE
    }

    def "a PDF holding an image that declares more pixels than a scan has is refused before it is decoded"() {
        given: "a page drawing a 1 x 1 image whose dictionary says 40,000 x 40,000 pixels - gigabytes once decoded"
        def pdf = new ByteArrayOutputStream()
        new PDDocument().withCloseable { document ->
            def page = new PDPage(PDRectangle.A4)
            document.addPage(page)
            def image = LosslessFactory.createFromImage(document, new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))
            image.COSObject.setInt(COSName.WIDTH, 40_000)
            image.COSObject.setInt(COSName.HEIGHT, 40_000)
            new PDPageContentStream(document, page).withCloseable { it.drawImage(image, 0, 0, 100, 100) }
            document.save(pdf)
        }

        when:
        DocumentImages.renderPages(pdf.toByteArray(), 1, 1)

        then:
        def e = thrown(ServiceException)
        e.message == "The image is too large - at most 40 megapixels"
    }

    private static byte[] png(int width, int height) {
        def out = new ByteArrayOutputStream()
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
        out.toByteArray()
    }

    /** A real 1 x 1 PNG whose header claims another size (with the header's checksum fixed). */
    private static byte[] pngDeclaring(int width, int height) {
        byte[] bytes = png(1, 1)
        ByteBuffer.wrap(bytes, 16, 8).putInt(width).putInt(height)
        def crc = new CRC32()
        crc.update(bytes, 12, 17)
        ByteBuffer.wrap(bytes, 29, 4).putInt((int) crc.value)
        bytes
    }
}
