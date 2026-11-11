package de.alpdesk.services.filestore;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;

/**
 * An entry of a store's folder, as its server lists it: a file, or a folder (a knowledge source walks into it; a task's
 * read_files step reads files only). Links are not listed.
 */
public record RemoteFile(String name, long size, @Nullable Instant modified, boolean folder) {

    /** "." and "..": the folder itself and its parent, which servers list as folders - never walked into. */
    static boolean isDotEntry(String name) {
        return name.equals(".") || name.equals("..");
    }

    /** A hidden entry (its name starts with ".") - neither read by a knowledge source nor offered as a folder. */
    public static boolean isHidden(String name) {
        return name.startsWith(".");
    }
}
