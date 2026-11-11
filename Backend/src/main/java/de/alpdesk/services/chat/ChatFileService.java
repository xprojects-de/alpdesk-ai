package de.alpdesk.services.chat;

import de.alpdesk.entities.chat.Conversation;
import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.entities.chat.FileOrigin;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.chat.ConversationFileRepository;
import de.alpdesk.repositories.chat.ConversationRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentImages;
import de.alpdesk.services.document.DocumentTextExtractor;
import de.alpdesk.services.sandbox.Sandboxes;
import de.alpdesk.services.tenant.TenantDatabases;
import de.alpdesk.services.tenant.TenantService;
import de.alpdesk.storage.FileStorage;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.format.ReadableBytes;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The files of a conversation (docs/architecture/ai-features.md "Files in the chat"): uploaded ones, read by
 * {@link ChatFileReader}, and the ones the model creates (tool create_file, {@link FileRenderer}) or a tool of the
 * connected software returns. Their content is in the {@link FileStorage} under {@code tenants/<tenant>/chat/<uuid>/},
 * their rows in the tenant database. They are not embedded: every question lists them, and the model reads what it
 * needs with the tool read_file.
 */
@Singleton
public class ChatFileService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatFileService.class);
    /** Text of one file: the model reads it in parts of ReadFileTool's size. */
    static final int MAX_TEXT_CHARACTERS = 2_000_000;
    /** Images of one file (an image, the pages of a scanned PDF) - the model gets all of them with one read. */
    static final int MAX_IMAGES = ChatFileReader.MAX_PAGES;
    public static final int MAX_FILES_PER_UPLOAD = 10;
    /**
     * One file - like a document of the knowledge base - and the files of one upload up to it together; a video is
     * limited by app.chat.video-max-size instead.
     */
    public static final int MAX_FILE_BYTES = 50 * 1024 * 1024;
    /** What a larger file is recognised by - it must be a video, and a video's type shows in its first bytes. */
    private static final int VIDEO_HEAD_BYTES = 64 * 1024;
    private static final int MAX_FILE_NAME = 200;
    private static final String OCTET_STREAM = "application/octet-stream";
    /**
     * The types a file a connected software returned keeps, with the extension a name without one gets: archives,
     * documents, text and the images the chat shows ({@link DocumentImages#IMAGES}) - nothing a browser would run
     * (HTML, SVG).
     */
    static final Map<String, String> RETURNED_TYPES = Map.ofEntries(
            Map.entry("application/zip", "zip"),
            Map.entry("application/gzip", "gz"),
            Map.entry("application/x-gzip", "gz"),
            Map.entry("application/x-tar", "tar"),
            Map.entry("application/x-7z-compressed", "7z"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/json", "json"),
            Map.entry("application/xml", "xml"),
            Map.entry("application/yaml", "yaml"),
            Map.entry("application/x-yaml", "yaml"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/x-log", "log"),
            Map.entry("text/csv", "csv"),
            Map.entry("text/markdown", "md"),
            Map.entry("text/xml", "xml"),
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/vnd.oasis.opendocument.text", "odt"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", "ods"));
    /** Types and extensions of returned files the model reads as text - servers name the types of text differently. */
    private static final Set<String> TEXT_TYPES = Set.of("application/json", "application/xml", "application/yaml",
            "application/x-yaml");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "log", "md", "csv", "tsv", "json", "xml", "yaml",
            "yml", "html", "htm", "properties", "ini", "conf", "cfg", "toml", "sql", "sh", "bat", "ps1", "java", "kt",
            "groovy", "gradle", "py", "js", "ts", "c", "h", "cpp", "cs", "go", "rs", "php", "rb");

    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final ConversationRepository conversations;
    private final ConversationFileRepository files;
    private final ChatFileReader reader;
    private final FileRenderer renderer;
    private final FileStorage storage;
    private final AudioTranscriber transcriber;
    private final Sandboxes sandboxes;
    private final long videoMaxBytes;

    public ChatFileService(TenantRepository tenants, TenantDatabases databases, ConversationRepository conversations,
                           ConversationFileRepository files, ChatFileReader reader, FileRenderer renderer,
                           FileStorage storage, AudioTranscriber transcriber, Sandboxes sandboxes,
                           @Value("${app.chat.video-max-size}") @ReadableBytes long videoMaxBytes) {
        this.tenants = tenants;
        this.databases = databases;
        this.conversations = conversations;
        this.files = files;
        this.reader = reader;
        this.renderer = renderer;
        this.storage = storage;
        this.transcriber = transcriber;
        this.sandboxes = sandboxes;
        this.videoMaxBytes = videoMaxBytes;
    }

    /**
     * Adds files to one of the owner's conversations (a user's, or one API key's of the client software), or to a new
     * one when conversationId is null. All files are read first: one unreadable file (400), one of a type not allowed
     * (415), a video without the tenant's sandbox (409) or more videos in the conversation than one's size (409) refuse
     * the whole upload.
     * 404 for another user's or key's conversation. A video above {@link #MAX_FILE_BYTES} is recognised by its first
     * bytes and streamed to the storage, never held in memory.
     */
    public UploadResult upload(Long tenantId, ConversationOwner owner, Long conversationId, List<Upload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            throw ServiceException.badRequest("chatFile.noFile", "No file was sent");
        }
        requireWithinLimits(uploads.stream().map(Upload::size).toList(), videoMaxBytes);
        Tenant tenant = tenant(tenantId);
        DataSource dataSource = dataSource(tenant);
        Conversation existing = conversationId == null ? null : requireConversation(dataSource, owner, conversationId);
        List<Prepared> prepared = new ArrayList<>();
        try {
            for (Upload upload : uploads) {
                // Checked file by file: ten files of 20 million characters each would otherwise be held at once.
                prepared.add(prepare(tenant, upload));
            }
            requireVideosWithinLimit(dataSource, existing, prepared);
            Conversation conversation = existing != null ? existing
                    : conversations.create(dataSource, owner, "Dateien: " + fileName(uploads.getFirst().fileName()));
            List<ConversationFile> stored = new ArrayList<>();
            for (Prepared file : prepared) {
                stored.add(store(tenant, owner.userId(), dataSource, conversation.id(), file));
            }
            return new UploadResult(conversation, stored);
        } finally {
            prepared.forEach(Prepared::close);
        }
    }

    /**
     * 400 for more than 10 files, a file larger than {@link #MAX_FILE_BYTES} and the video size, or more than
     * {@link #MAX_FILE_BYTES} in the files up to it together - by the sizes the parts declare, before any is read.
     */
    static void requireWithinLimits(List<Long> sizes, long videoMaxBytes) {
        if (sizes.size() > MAX_FILES_PER_UPLOAD) {
            throw ServiceException.badRequest("chatFile.tooMany", "At most " + MAX_FILES_PER_UPLOAD + " files at once",
                    Map.of("max", String.valueOf(MAX_FILES_PER_UPLOAD)));
        }
        long total = 0;
        for (long size : sizes) {
            if (size > Math.max(MAX_FILE_BYTES, videoMaxBytes)) {
                throw tooLarge(videoMaxBytes);
            }
            if (size <= MAX_FILE_BYTES) {
                total += size;
            }
        }
        if (total > MAX_FILE_BYTES) {
            throw ServiceException.badRequest("chatFile.uploadTooLarge", "The files of one upload may have at most "
                    + megabytes(MAX_FILE_BYTES) + " MB together",
                    Map.of("maxMb", String.valueOf(megabytes(MAX_FILE_BYTES))));
        }
    }

    /**
     * Every video of the conversation goes into the sandbox's session, which holds about two of the largest
     * (code-execution.md "Videos": the video takes half, frames and clips the rest) - so together they keep to the size
     * of one (409). An expired video no longer counts.
     */
    private void requireVideosWithinLimit(DataSource dataSource, @Nullable Conversation conversation,
                                          List<Prepared> prepared) {
        long total = 0;
        for (Prepared file : prepared) {
            if (file.read().contentType().startsWith("video/")) {
                total += file.upload().size();
            }
        }
        if (total == 0) {
            return;
        }
        if (conversation != null) {
            for (ConversationFile file : files.findByConversation(dataSource, conversation.id())) {
                if (!file.isExpired() && file.contentType().startsWith("video/")) {
                    total += file.sizeBytes();
                }
            }
        }
        if (total > videoMaxBytes) {
            throw ServiceException.conflict("chatFile.videosTooLarge", "The videos of one conversation may have at most "
                            + megabytes(videoMaxBytes) + " MB together - start a new conversation, or delete a video",
                    Map.of("videoMaxMb", String.valueOf(megabytes(videoMaxBytes))));
        }
    }

    private static ServiceException tooLarge(long videoMaxBytes) {
        return ServiceException.badRequest("chatFile.tooLarge", "A file may have at most " + megabytes(MAX_FILE_BYTES)
                        + " MB, a video " + megabytes(videoMaxBytes) + " MB",
                Map.of("maxMb", String.valueOf(megabytes(MAX_FILE_BYTES)),
                        "videoMaxMb", String.valueOf(megabytes(videoMaxBytes))));
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    /**
     * An upload read and checked: up to {@link #MAX_FILE_BYTES} read whole (Tika needs a DOCX's end to know it), above it
     * recognised by its first bytes - it must be a video - and kept open to be streamed to the storage.
     */
    private Prepared prepare(Tenant tenant, Upload upload) {
        String name = fileName(upload.fileName());
        if (upload.size() <= MAX_FILE_BYTES) {
            byte[] content = upload.bytes();
            ChatFileReader.ReadFile read = withinLimits(reader.read(name, content));
            requireVideoAllowed(tenant, read.contentType(), content.length);
            return new Prepared(upload, content, null, read);
        }
        InputStream stream = upload.open();
        try {
            BufferedInputStream buffered = new BufferedInputStream(stream, VIDEO_HEAD_BYTES);
            buffered.mark(VIDEO_HEAD_BYTES);
            String type = reader.detect(name, buffered.readNBytes(VIDEO_HEAD_BYTES));
            buffered.reset();
            if (!type.startsWith("video/")) {
                throw tooLarge(videoMaxBytes);
            }
            requireVideoAllowed(tenant, type, upload.size());
            return new Prepared(upload, null, buffered, new ChatFileReader.ReadFile(type, null, List.of(), null));
        } catch (IOException e) {
            closeQuietly(stream);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            closeQuietly(stream);
            throw e;
        }
    }

    /**
     * A video is examined with code in the tenant's sandbox - without one entered, nothing could look at it (409); and
     * it has its own size limit.
     */
    private void requireVideoAllowed(Tenant tenant, String type, long size) {
        if (!type.startsWith("video/")) {
            return;
        }
        if (tenant.sandboxToken() == null || Sandboxes.addresses(tenant.sandboxUrls()).isEmpty()) {
            throw ServiceException.conflict("chatFile.videoNeedsSandbox", "A video can only be examined with the "
                    + "tenant's code execution - an admin enters its sandbox at the tenant");
        }
        // Not answering right now: taken - whether it is the video image shows when the model asks for the video.
        if (sandboxes.video(tenant) == Sandboxes.Video.NO) {
            throw ServiceException.conflict("chatFile.videoNeedsVideoSandbox", "The tenant's code execution examines "
                    + "no videos - an admin gives it the video sandbox");
        }
        if (size > videoMaxBytes) {
            throw tooLarge(videoMaxBytes);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // nothing left to do with it
        }
    }

    /** An upload ready to store: its content read whole, or a video's stream (closed after the upload either way). */
    private record Prepared(Upload upload, byte[] content, InputStream stream, ChatFileReader.ReadFile read) {
        void close() {
            if (stream != null) {
                closeQuietly(stream);
            }
        }
    }

    public List<ConversationFile> findByConversation(Long tenantId, ConversationOwner owner, Long conversationId) {
        DataSource dataSource = dataSource(tenant(tenantId));
        requireConversation(dataSource, owner, conversationId);
        return files.findByConversation(dataSource, conversationId);
    }

    /** The file and its content - 404 for a file of another owner's conversation. The caller closes the stream. */
    public OpenedFile open(Long tenantId, ConversationOwner owner, Long fileId) {
        ConversationFile file = requireFile(dataSource(tenant(tenantId)), owner, fileId);
        if (file.isExpired()) {
            throw ServiceException.notFound("chatFile.expired", "The file was deleted after its retention time");
        }
        InputStream content = storage.open(file.fileKey())
                .orElseThrow(() -> ServiceException.notFound("chatFile.contentMissing",
                        "The file's content is missing"));
        return new OpenedFile(file, content);
    }

    public boolean delete(Long tenantId, ConversationOwner owner, Long fileId) {
        DataSource dataSource = dataSource(tenant(tenantId));
        ConversationFile file = files.findByIdAndOwner(dataSource, fileId, owner).orElse(null);
        if (file == null || !files.delete(dataSource, fileId)) {
            return false;
        }
        deleteContent(file.storageDir());
        return true;
    }

    /** The files of a conversation that is being deleted (their rows go with it by cascade). */
    List<ConversationFile> findForDeletion(DataSource dataSource, Long conversationId) {
        return files.findByConversation(dataSource, conversationId);
    }

    void deleteContent(String storageDir) {
        try {
            storage.deleteAll(storageDir);
        } catch (RuntimeException e) {
            // The row is gone; content left behind is unreachable - a 500 would not help anyone.
            LOG.warn("Content of a deleted chat file could not be removed: {}", e.toString());
        }
    }

    /**
     * The question as the model gets it: the list of the conversation's files (the model reads them with the tool
     * read_file when it needs them - they are not sent with every question), then the question itself.
     */
    String questionWithFileList(DataSource dataSource, List<ConversationFile> conversationFiles, String question) {
        if (conversationFiles.isEmpty()) {
            return question;
        }
        Set<Long> transcripts = files.findIdsWithText(dataSource, conversationFiles.getFirst().conversationId());
        StringBuilder text = new StringBuilder("The files of this conversation - read them with the tool read_file:\n");
        for (ConversationFile file : conversationFiles) {
            text.append("- file_id ").append(file.id()).append(": \"").append(file.fileName()).append("\" (")
                    .append(file.contentType()).append(", ").append(file.sizeBytes()).append(" bytes");
            if (file.isExpired()) {
                text.append(", ").append(file.expiredNote());
            } else if (file.origin() == FileOrigin.GENERATED) {
                text.append(", created by you");
            } else if (file.imageCount() > 0 && !file.contentType().startsWith("image/")) {
                text.append(", scanned: ").append(file.imageCount()).append(" page images, no text");
            } else if (file.isAudio()) {
                text.append(transcripts.contains(file.id()) ? ", transcript available" : ", no transcript");
            }
            text.append(")\n");
        }
        return text.append("\n=== Question ===\n").append(question).toString();
    }

    /** The conversation's files - none for a question without a conversation. */
    public List<ConversationFile> findByConversation(DataSource dataSource, Long conversationId) {
        return conversationId == null ? List.of() : files.findByConversation(dataSource, conversationId);
    }

    /** A file of the conversation, for the tool read_file - empty for one of another conversation. */
    public Optional<ConversationFile> findInConversation(DataSource dataSource, Long conversationId, Long fileId) {
        return files.findByConversation(dataSource, conversationId).stream().filter(file -> file.id().equals(fileId)).findFirst();
    }

    /** The text the model reads of a file (extracted on upload, written for a created one) - null for an image. */
    public String text(DataSource dataSource, ConversationFile file) {
        return files.findText(dataSource, file.id()).orElse(null);
    }

    /** The images stored for the model: an uploaded image, the pages of a scanned PDF. */
    public List<byte[]> storedImages(ConversationFile file) {
        List<byte[]> images = new ArrayList<>();
        for (int number = 1; number <= file.imageCount(); number++) {
            images.add(readAll(file.imageKey(number)));
        }
        return images;
    }

    /** The original content of a file. */
    public byte[] content(ConversationFile file) {
        return readAll(file.fileKey());
    }

    /** The original content of a file, streamed - for the sandbox, which takes a video without it held in memory. */
    public InputStream openContent(ConversationFile file) throws IOException {
        return storage.open(file.fileKey()).orElseThrow(() -> new IOException("the content of a chat file is missing"));
    }

    /**
     * The type a file handed out of the sandbox is stored with, by its name's extension - one of {@link #RETURNED_TYPES}
     * (a frame, a clip, a CSV, a document), else a plain download. Of two types with one extension (xml, gz) the first
     * by name, so the same name always gets the same type.
     */
    public static String typeOfName(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("jpeg")) {
            return "image/jpeg";
        }
        String found = null;
        for (Map.Entry<String, String> type : RETURNED_TYPES.entrySet()) {
            if (type.getValue().equals(extension) && (found == null || type.getKey().compareTo(found) < 0)) {
                found = type.getKey();
            }
        }
        return found == null ? OCTET_STREAM : found;
    }

    /** The model's copy of audio (MP3). */
    public byte[] media(ConversationFile file) {
        return readAll(file.mediaKey());
    }

    /**
     * Writes a file the model creates and stores its content right away - its row follows with the answer
     * ({@link #attach}), or the content is removed again ({@link #discard}) when the answer fails.
     */
    public PendingFile createGenerated(Tenant tenant, String requestedName, OutputFormat format, String content) {
        FileRenderer.Rendered rendered = renderer.render(format, content);
        String name = withExtension(fileName(requestedName), format);
        String storageDir = newStorageDir(tenant);
        storage.put(storageDir + "file", rendered.content(), rendered.contentType());
        return new PendingFile(name, rendered.contentType(), rendered.content().length, storageDir, content, 0);
    }

    /**
     * Stores an image the image model created (tool create_image) like {@link #createGenerated}, with the JPEG copy the
     * chat model looks at when it reads the image - and the image model gets the image itself when it is changed again.
     */
    public PendingFile createImage(Tenant tenant, String requestedName, byte[] content, String contentType) {
        byte[] copyForTheModel = DocumentImages.imageAsJpeg(content);
        String storageDir = newStorageDir(tenant);
        storage.put(storageDir + "file", content, contentType);
        storage.put(storageDir + "image-1.jpg", copyForTheModel, "image/jpeg");
        return new PendingFile(fileName(requestedName), contentType, content.length, storageDir, null, 1);
    }

    /**
     * Stores a file a tool of the connected software returned (an MCP embedded resource or image) like
     * {@link #createGenerated}: the content as it came. Its type only from {@link #RETURNED_TYPES}, anything else is a
     * plain download - the software is the tenant's, a type like text/html or image/svg+xml would otherwise reach the
     * browser as it said. A document or an image is read like an upload ({@link ChatFileReader}: its text, a scan's
     * pages or the image as the JPEG copy the model looks at) - and takes the type its content shows, when it differs
     * from the one the server said; one the reader refuses is a plain download. The model reads the text of a text
     * file, else it only sees that the file is there. readForModel false: the file only goes with ask_assistant's
     * answer, no read_file follows - Tika and a scan's page images would be work thrown away with it.
     */
    public PendingFile createReturned(Tenant tenant, String requestedName, byte[] content, @Nullable String contentType,
                                      boolean readForModel) {
        String type = returnedType(contentType);
        String name = fileName(requestedName);
        if (!name.contains(".") && RETURNED_TYPES.containsKey(type)) {
            name = name + "." + RETURNED_TYPES.get(type);
        }
        ChatFileReader.ReadFile read = readForModel ? readReturned(name, content, type) : null;
        if (read != null && !read.contentType().equals(type)) {
            type = returnedType(read.contentType());
        }
        // An image the reader refuses is broken - no copy a model could look at, none a browser shows: a plain
        // download, so neither read_file nor create_image takes it for an image.
        if (readForModel && read == null && DocumentImages.IMAGES.contains(type)) {
            type = OCTET_STREAM;
        }
        String text = read != null ? read.text() : readsAsText(type, name) ? utf8Text(content) : null;
        List<byte[]> images = read == null ? List.of() : read.images().subList(0, Math.min(read.images().size(), MAX_IMAGES));
        String storageDir = newStorageDir(tenant);
        storage.put(storageDir + "file", content, type);
        for (int i = 0; i < images.size(); i++) {
            storage.put(storageDir + "image-" + (i + 1) + ".jpg", images.get(i), "image/jpeg");
        }
        return new PendingFile(name, type, content.length, storageDir, text == null ? null : cutText(text), images.size());
    }

    /** A returned document or image read like an upload - null for any other file, or one the reader refuses. */
    private @Nullable ChatFileReader.ReadFile readReturned(String name, byte[] content, String type) {
        if (!DocumentImages.IMAGES.contains(type) && !DocumentTextExtractor.ALLOWED.containsKey(type)) {
            return null;
        }
        try {
            return reader.read(name, content);
        } catch (ServiceException e) {
            return null;
        }
    }

    /**
     * The text of a file a connected software's tool returned, for the model to read in the tool's result - null for
     * one that is no text (an archive, a PDF, a Latin-1 log): the rules of {@link #createReturned}, without storing.
     */
    public static @Nullable String textOfReturned(String name, byte[] content, @Nullable String contentType) {
        return readsAsText(returnedType(contentType), fileName(name)) ? utf8Text(content) : null;
    }

    /**
     * A file a connected software's tool returned, as the MCP server passes it on unchanged (roadmap point 41): its name
     * and type by the rules of {@link #createReturned} (the type from the list, an extension for a name without one), its
     * text where it reads as text - nothing stored.
     */
    public static ChatService.DeliveredFile passedOn(String requestedName, byte[] content, @Nullable String contentType) {
        String type = returnedType(contentType);
        String name = fileName(requestedName);
        if (!name.contains(".") && RETURNED_TYPES.containsKey(type)) {
            name = name + "." + RETURNED_TYPES.get(type);
        }
        return new ChatService.DeliveredFile(name, type, content, readsAsText(type, name) ? utf8Text(content) : null);
    }

    /** The type a returned file is offered with: one of {@link #RETURNED_TYPES}, without parameters, else octet-stream. */
    static String returnedType(@Nullable String contentType) {
        String type = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return RETURNED_TYPES.containsKey(type) ? type : OCTET_STREAM;
    }

    private static boolean readsAsText(String type, String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || TEXT_TYPES.contains(type) || TEXT_EXTENSIONS.contains(extension);
    }

    /** The content as UTF-8 text - null when it is none (a binary file named ".log", a Latin-1 log). */
    static @Nullable String utf8Text(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String cutText(String text) {
        return text.length() > MAX_TEXT_CHARACTERS ? text.substring(0, MAX_TEXT_CHARACTERS) : text;
    }

    /**
     * The rows of the files created for an answer, now that answer and conversation exist. A file leaves the pending list
     * once its row is stored: storing the rest of the answer may still fail, and {@link #discard} must not remove the
     * content a stored row points to.
     */
    List<ConversationFile> attach(DataSource dataSource, Long conversationId, Long messageId, List<PendingFile> pending) {
        List<ConversationFile> attached = new ArrayList<>();
        while (!pending.isEmpty()) {
            PendingFile file = pending.getFirst();
            attached.add(files.insert(dataSource, conversationId, messageId, FileOrigin.GENERATED, file.fileName(),
                    file.contentType(), file.sizeBytes(), file.storageDir(), file.text(), file.imageCount()));
            pending.removeFirst();
        }
        return attached;
    }

    /** A created file's content, for ask_assistant's answer, which sends it instead of keeping it. */
    byte[] content(PendingFile file) {
        return readAll(file.storageDir() + "file");
    }

    /**
     * Whether the file's content is text - code, Markdown, a log -, not only a document with text extracted from it:
     * a text type, or any type with text read as UTF-8 (a returned ".log" sent as octet-stream).
     */
    static boolean isText(PendingFile file) {
        String type = file.contentType();
        return type.startsWith("text/") || TEXT_TYPES.contains(type)
                || (type.equals(OCTET_STREAM) && file.text() != null);
    }

    void discard(List<PendingFile> pending) {
        pending.forEach(file -> deleteContent(file.storageDir()));
    }

    /**
     * Stores the file and what the model is sent - for audio also its transcript (text), when it can be written. Its
     * content is removed again when its row cannot be stored.
     */
    private ConversationFile store(Tenant tenant, Long userId, DataSource dataSource, Long conversationId, Prepared file) {
        String storageDir = newStorageDir(tenant);
        try {
            return storeIn(storageDir, tenant, userId, dataSource, conversationId, file);
        } catch (RuntimeException e) {
            deleteContent(storageDir);
            throw e;
        }
    }

    private ConversationFile storeIn(String storageDir, Tenant tenant, Long userId, DataSource dataSource,
                                     Long conversationId, Prepared file) {
        ChatFileReader.ReadFile read = file.read();
        // Transcribed before anything is stored: a restart during the minutes it can take must not leave content
        // without its row, which no retention would ever delete.
        String text = read.media() == null ? read.text()
                : transcriber.transcribe(tenant, userId, read.media()).orElse(null);
        if (file.content() != null) {
            storage.put(storageDir + "file", file.content(), read.contentType());
        } else {
            storage.put(storageDir + "file", file.stream(), read.contentType());
        }
        for (int i = 0; i < read.images().size(); i++) {
            storage.put(storageDir + "image-" + (i + 1) + ".jpg", read.images().get(i), "image/jpeg");
        }
        if (read.media() != null) {
            storage.put(storageDir + "media", read.media(), "audio/mpeg");
        }
        long size = file.content() != null ? file.content().length : file.upload().size();
        return files.insert(dataSource, conversationId, null, FileOrigin.UPLOADED, fileName(file.upload().fileName()),
                read.contentType(), size, storageDir, text, read.images().size());
    }

    /** Per file: what one read_file call can still handle (text is read in parts, images all at once). */
    private static ChatFileReader.ReadFile withinLimits(ChatFileReader.ReadFile file) {
        if (file.text() != null && file.text().length() > MAX_TEXT_CHARACTERS) {
            throw ServiceException.badRequest("chatFile.tooMuchText", "A file may have at most "
                    + MAX_TEXT_CHARACTERS + " characters of text",
                    Map.of("max", String.valueOf(MAX_TEXT_CHARACTERS)));
        }
        if (file.images().size() > MAX_IMAGES) {
            throw ServiceException.badRequest("chatFile.tooManyImages", "A file may have at most " + MAX_IMAGES
                    + " images (pages of a scan)",
                    Map.of("max", String.valueOf(MAX_IMAGES)));
        }
        return file;
    }

    /** 404 for content gone - a tool tells the model, the answer goes on. */
    private byte[] readAll(String key) {
        try (InputStream in = storage.open(key).orElseThrow(() -> ServiceException.notFound("chatFile.contentMissing",
                "The file's content is missing"))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String newStorageDir(Tenant tenant) {
        return TenantService.storagePrefix(tenant.id()) + "chat/" + UUID.randomUUID() + "/";
    }

    /** A file name without a path, control characters or quotes - it ends up in a download header. */
    public static String fileName(String name) {
        String plain = name == null ? "" : name.replace('\\', '/');
        plain = plain.substring(plain.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (plain.isEmpty() || plain.equals(".") || plain.equals("..")) {
            plain = "datei";
        }
        return plain.length() <= MAX_FILE_NAME ? plain : plain.substring(0, MAX_FILE_NAME);
    }

    private static String withExtension(String name, OutputFormat format) {
        return name.toLowerCase().endsWith("." + format.extension) ? name : name + "." + format.extension;
    }

    private Conversation requireConversation(DataSource dataSource, ConversationOwner owner, Long conversationId) {
        return conversations.findByIdAndOwner(dataSource, conversationId, owner)
                .orElseThrow(() -> ServiceException.notFound("conversation.notFound", "Conversation not found"));
    }

    private ConversationFile requireFile(DataSource dataSource, ConversationOwner owner, Long fileId) {
        return files.findByIdAndOwner(dataSource, fileId, owner)
                .orElseThrow(() -> ServiceException.notFound("chatFile.notFound", "File not found"));
    }

    private Tenant tenant(Long tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ServiceException.notFound("tenant.notFound",
                "Tenant not found"));
    }

    private DataSource dataSource(Tenant tenant) {
        return databases.dataSource(tenant.databaseName());
    }

    /** A file as it was sent. */
    /** A file of an upload: its name, its size as the part declares it, its content - opened once. */
    public record Upload(String fileName, long size, Content content) {

        @FunctionalInterface
        public interface Content {
            InputStream open() throws IOException;
        }

        public static Upload of(String fileName, byte[] content) {
            return new Upload(fileName, content.length, () -> new ByteArrayInputStream(content));
        }

        InputStream open() {
            try {
                return content.open();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        byte[] bytes() {
            try (InputStream in = open()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public record UploadResult(Conversation conversation, List<ConversationFile> files) {
    }

    public record OpenedFile(ConversationFile file, InputStream content) {
    }

    /**
     * A created file whose content is stored and whose row waits for the answer. text: what the model reads of it (null
     * for an image); imageCount: its images for the model (1 for an image).
     */
    public record PendingFile(String fileName, String contentType, long sizeBytes, String storageDir,
                              @Nullable String text, int imageCount) {
    }
}
