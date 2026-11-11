package de.alpdesk.controller.chat;

import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.List;

/** What the chat controllers of the frontend and of the client software share around files. */
public final class ChatFiles {

    private ChatFiles() {
    }

    /**
     * The uploaded parts, not read yet: the server takes requests up to a ZIP archive's size and keeps larger parts on
     * disk - the service checks their number and declared sizes before it reads any, and streams a video to the storage.
     */
    public static List<ChatFileService.Upload> read(Publisher<CompletedFileUpload> parts) {
        List<CompletedFileUpload> uploads = Flux.from(parts).collectList().block();
        if (uploads == null || uploads.isEmpty()) {
            throw ServiceException.badRequest("chatFile.noFileField", "No file was sent (multipart field \"files\")");
        }
        return uploads.stream()
                .map(upload -> new ChatFileService.Upload(upload.getFilename(), upload.getSize(), upload::getInputStream))
                .toList();
    }

    /** The file as a download, with its name in the Content-Disposition header. */
    public static HttpResponse<StreamedFile> download(ChatFileService.OpenedFile opened) {
        return HttpResponse.ok(new StreamedFile(opened.content(), MediaType.of(opened.file().contentType()))
                .attach(opened.file().fileName()));
    }
}
