package de.alpdesk.entities.chat;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;

/** A row of a tenant database's table "conversation_file" - without its text, which is read only to ask the model. */
public record ConversationFile(
        Long id,
        Long conversationId,
        /** The answer that created a generated file; null for an uploaded one. */
        @Nullable Long messageId,
        FileOrigin origin,
        String fileName,
        String contentType,
        long sizeBytes,
        /** The file's "directory" in the file storage, ending with a slash. */
        String storageDir,
        /** How many images the model is sent (an uploaded image, the pages of a scanned PDF). */
        int imageCount,
        Instant createdAt,
        /** When the file was deleted from the storage after its retention time; null while it is there. */
        @Nullable Instant expiredAt
) {
    public boolean isExpired() {
        return expiredAt != null;
    }

    /** What the model is told of an expired file - an uploaded one the user can upload again. */
    public String expiredNote() {
        return "expired - deleted after its retention time" + (origin == FileOrigin.UPLOADED
                ? "; ask the user to upload it again" : "");
    }

    /** Where the file itself is stored. */
    public String fileKey() {
        return storageDir + "file";
    }

    /** Where the model's copy of audio (MP3) is stored. */
    public String mediaKey() {
        return storageDir + "media";
    }

    public boolean isAudio() {
        return contentType.startsWith("audio/");
    }

    public boolean isVideo() {
        return contentType.startsWith("video/");
    }

    /** The type of the model's copy of audio. */
    public String mediaType() {
        return "audio/mpeg";
    }

    /** Where the n-th image the model is sent is stored (1-based), always a JPEG. */
    public String imageKey(int number) {
        return storageDir + "image-" + number + ".jpg";
    }
}
