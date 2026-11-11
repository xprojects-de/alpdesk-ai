package de.alpdesk.services.chat;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Turns uploaded audio into the small copy a model is sent, with ffmpeg (in the container image; locally from the PATH,
 * app.media.ffmpeg): MP3, mono, 16 kHz, 32 kbit/s - what both Gemini and OpenAI read, speech needs no more. The
 * original stays for the download. A video gets no copy: the model examines it with code in the tenant's sandbox
 * (docs/architecture/code-execution.md "Videos"). ffmpeg runs on a time limit: a file it
 * cannot read, or that takes too long, is refused.
 */
@Singleton
public class MediaConverter {

    private static final Logger LOG = LoggerFactory.getLogger(MediaConverter.class);
    /** What the providers take inline per request - a longer recording does not fit. */
    static final int MAX_MODEL_BYTES = 20 * 1024 * 1024;
    private static final List<String> AUDIO = List.of("-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame", "-b:a", "32k",
            "-f", "mp3");

    private final String ffmpeg;
    private final Duration timeout;
    private volatile Boolean available;

    public MediaConverter(@Value("${app.media.ffmpeg}") String ffmpeg, @Value("${app.media.timeout}") Duration timeout) {
        this.ffmpeg = ffmpeg;
        this.timeout = timeout;
    }

    /** The model's copy of an audio file (audio/mpeg). 400 if ffmpeg cannot read it or it is too long. */
    public byte[] audio(byte[] original) {
        return convert(original, AUDIO, ".mp3", "audio");
    }

    private byte[] convert(byte[] original, List<String> options, String suffix, String kind) {
        assertAvailable();
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("alpdesk-media-", ".in");
            output = Files.createTempFile("alpdesk-media-", suffix);
            Files.write(input, original);
            // Only the local file, nothing over the network - a format that points at other inputs gets no further.
            List<String> command = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-protocol_whitelist", "file", "-i", input.toString()));
            command.addAll(options);
            // ffmpeg stops writing one byte past what the models take: a long recording that is small on disk
            // (hours of speech at a low bitrate) would otherwise fill /tmp and then the heap before it is refused.
            command.addAll(List.of("-fs", String.valueOf(MAX_MODEL_BYTES + 1)));
            command.add(output.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw ServiceException.badRequest("media.convertTimeout", "The " + kind + " takes too long to convert",
                        Map.of("kind", kind));
            }
            if (process.exitValue() != 0) {
                throw ServiceException.badRequest("media.unreadable", "The " + kind + " could not be read",
                        Map.of("kind", kind));
            }
            byte[] converted = Files.readAllBytes(output);
            if (converted.length == 0) {
                throw ServiceException.badRequest("media.unreadable", "The " + kind + " could not be read",
                        Map.of("kind", kind));
            }
            if (converted.length > MAX_MODEL_BYTES) {
                String minutes = "80";
                throw ServiceException.badRequest("media.tooLong", "The " + kind
                        + " is too long for the AI models (about " + minutes + " minutes at most)",
                        Map.of("kind", kind, "minutes", minutes));
            }
            return converted;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not convert the " + kind, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ServiceException.unavailable("media.interrupted", "The " + kind
                    + " conversion was interrupted - please try again",
                    Map.of("kind", kind));
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    /** 415 when the server has no ffmpeg - audio cannot be taken then. */
    private void assertAvailable() {
        if (available == null) {
            try {
                Process process = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                available = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
            } catch (IOException e) {
                available = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!available) {
                LOG.warn("ffmpeg ({}) is not available - audio uploads are refused", ffmpeg);
            }
        }
        if (!available) {
            throw new ServiceException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "media.notSupported",
                    "Audio is not supported on this server");
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warn("Temporary media file {} could not be deleted", path);
            }
        }
    }
}
