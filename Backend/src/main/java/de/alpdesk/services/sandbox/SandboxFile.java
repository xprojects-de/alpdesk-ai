package de.alpdesk.services.sandbox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A file a session's code finds in data/ - under its name, with its size and how to read it: a video comes streamed from
 * the storage, never whole in memory.
 */
public record SandboxFile(String name, long size, Content content) {

    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }

    public static SandboxFile of(String name, byte[] content) {
        return new SandboxFile(name, content.length, () -> new ByteArrayInputStream(content));
    }
}
