package de.alpdesk.dto.filestore;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * The subfolders of one folder of a store, to choose one: path relative to the store's folder ("" its folder itself),
 * the folders' names sorted, truncated - more than the list holds.
 */
@Serdeable
public record FileStoreFoldersResponse(String path, List<String> folders, boolean truncated) {
}
