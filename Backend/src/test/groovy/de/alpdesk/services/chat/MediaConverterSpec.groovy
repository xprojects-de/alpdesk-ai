package de.alpdesk.services.chat

import de.alpdesk.services.common.ServiceException
import io.micronaut.http.HttpStatus
import spock.lang.Requires
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The models' copy of a recording (ffmpeg): a recording too long for the models is refused - also one that is small
 * on disk (hours at a low bitrate), which ffmpeg stops writing one byte past the limit instead of filling /tmp and
 * the heap first.
 */
class MediaConverterSpec extends Specification {

    @Requires({ MediaConverterSpec.ffmpegAvailable() })
    def "a recording longer than the models take is refused as too long"() {
        given: "100 minutes of silence, a few MB as Opus - the models' MP3 of it would be 24 MB"
        def converter = new MediaConverter("ffmpeg", Duration.ofMinutes(10))
        byte[] recording = silence(100 * 60)
        assert recording.length < MediaConverter.MAX_MODEL_BYTES / 4

        when:
        converter.audio(recording)

        then:
        def refused = thrown(ServiceException)
        refused.code == "media.tooLong"
    }

    @Requires({ MediaConverterSpec.ffmpegAvailable() })
    def "a short recording is converted"() {
        given:
        def converter = new MediaConverter("ffmpeg", Duration.ofMinutes(1))

        when:
        byte[] mp3 = converter.audio(silence(2))

        then:
        mp3.length > 0
        mp3.length < MediaConverter.MAX_MODEL_BYTES
    }

    /** Silence of the given seconds as Opus in an OGG container - about 20 KB a minute. */
    private static byte[] silence(int seconds) {
        Path file = Files.createTempFile("alpdesk-silence-", ".ogg")
        try {
            def process = new ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                    "anullsrc=r=8000:cl=mono", "-t", seconds.toString(), "-c:a", "libopus", "-b:a", "6k",
                    file.toString()).redirectErrorStream(true).start()
            assert process.waitFor() == 0: process.inputStream.text
            Files.readAllBytes(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    def "a server without ffmpeg refuses audio as not supported"() {
        when:
        new MediaConverter("no-such-ffmpeg", Duration.ofMinutes(1)).audio(new byte[16])

        then:
        def refused = thrown(ServiceException)
        refused.status() == HttpStatus.UNSUPPORTED_MEDIA_TYPE
        refused.code == "media.notSupported"
    }

    def "an ffmpeg taking too long is stopped, and its files are deleted"() {
        given: "an ffmpeg that answers -version and then hangs"
        Path slow = Files.createTempFile("slow-ffmpeg", ".sh")
        Files.writeString(slow, '#!/bin/sh\nif [ "$1" = "-version" ]; then exit 0; fi\nexec sleep 30\n')
        slow.toFile().setExecutable(true)
        def converter = new MediaConverter(slow.toString(), Duration.ofSeconds(1))
        def before = mediaFiles()

        when:
        converter.audio(new byte[16])

        then:
        def refused = thrown(ServiceException)
        refused.code == "media.convertTimeout"
        mediaFiles() == before

        cleanup:
        Files.deleteIfExists(slow)
    }

    private static Set<String> mediaFiles() {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"))
        Files.list(temp).withCloseable { files ->
            files.map { it.fileName.toString() }.filter { it.startsWith("alpdesk-media-") }.toList() as Set
        }
    }

    static boolean ffmpegAvailable() {
        try {
            new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
        } catch (IOException ignored) {
            false
        }
    }
}
