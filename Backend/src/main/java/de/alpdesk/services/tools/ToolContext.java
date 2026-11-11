package de.alpdesk.services.tools;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.CallTokens;
import de.alpdesk.services.ai.Spent;
import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.chat.SentFile;
import de.alpdesk.services.document.KnowledgeScope;
import de.alpdesk.services.mcp.McpTools;
import de.alpdesk.services.sandbox.SandboxSession;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.micronaut.core.annotation.Nullable;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Who asks and where, for the tools of one answer - and what they leave for it: the files created (stored with the
 * answer) and the documents found (its sources). Used by one answer only, but its rounds may run on different threads
 * one after the other (the provider's client) - never at the same time.
 */
public final class ToolContext {

    private final Tenant tenant;
    private final Asker asker;
    private final AiModel model;
    private final Long userId;
    private final KnowledgeScope knowledge;
    private final DataSource dataSource;
    private final Long conversationId;
    private final boolean conversationHasFiles;
    private final int searchResults;
    private final Files files;
    private final List<ChatFileService.PendingFile> createdFiles = new ArrayList<>();
    private final Set<String> sources = new LinkedHashSet<>();
    /** The connected software's tools this question may use - set by the Toolbox when it offers the tools. */
    private List<McpTools.Offered> softwareTools = List.of();
    /** The connected software this question leaves out (the asker's choice, roadmap point 51). */
    private Set<Long> excludedConnections = Set.of();
    /** The choice to keep with the conversation; null: the conversation's stays as it is. */
    private List<Long> exclusionToKeep;
    /** A changing call the model made - the answer stops there and waits for the user. */
    private PendingCall pendingCall;
    /** Files sent with the question too large to go with it whole - read through search_file and read_file_lines. */
    private List<ExploredFile> exploredFiles = List.of();
    /** The documents similarity_search found in this answer, by the name it gave them - the ones read_document reads. */
    private final Map<String, FoundDocument> foundDocuments = new HashMap<>();
    /** The characters read_document returned in this answer - it stops at its limit. */
    private int documentCharactersRead;
    /** The calls of the image model in this answer - each is billed, also one that created no image. */
    private int imageCalls;
    /** Whether the asker may search the web: its group, its API key, the MCP server (WebSearchTool). */
    private boolean searchesWeb;
    /**
     * The key the question was asked with - the client software's API key or an MCP key, by the asker: the connected
     * software it may use; null for a user or a task.
     */
    private Long keyId;
    /** Whether this answer searched the company's documents - web_search waits for it. */
    private boolean documentsSearched;
    /** The web searches of this answer - each is billed. */
    private int webSearches;
    /** The images view_images showed in this answer - every later round sends them all again. */
    private int imagesViewed;
    /** The answer's session for run_python and run_shell - opened at the first call, closed with the answer. */
    private SandboxSession sandbox;
    /** Every file sent with the question - small or large -, for the sandbox's data/ should its code run. */
    private List<SentFile> sentFiles = List.of();
    /**
     * The model examines a video in this answer: one of the conversation that has not expired, and the tenant's sandbox
     * is the video image - it takes more rounds and rules of its own.
     */
    private boolean examinesVideo;
    /** The only tools this answer may use, by name (a task's step); null: whatever is available. */
    private Set<String> onlyTools;
    /** A task's assistant prompt: the connections its text names (the asker TASK has no key or levels). */
    private List<Long> taskConnections = List.of();
    /** Asked before every round of the answer: true stops it there (a task's run that was cancelled). */
    private BooleanSupplier stopWhen = () -> false;
    /** What the answer's rounds used and cost so far - a task's step counts into its own, kept when it fails. */
    private Spent spent = new Spent();

    /**
     * Who asks - it decides which tools of the connected software a question gets (docs/architecture/mcp.md): a user
     * those of their levels, the MCP server and the client software the reading ones opened to each. Told, never
     * derived: the client software with an API key that keeps no history asks without a conversation too. A task's step
     * (TASK) asks no software at all.
     */
    public enum Asker { USER, CLIENT_SOFTWARE, MCP_SERVER, TASK }

    /** What becomes of a file the answer creates (create_file, create_image) or a connected software's tool returns. */
    public enum Files {
        /** Kept in the conversation and shown below the answer: a user's chat, an API key that keeps the history. */
        KEPT,
        /** Sent with the answer, kept nowhere: the MCP server's ask_assistant (docs/architecture/mcp.md). */
        DELIVERED,
        /** None: the client software with an API key that keeps no history. */
        NONE
    }

    /** A document a search found: its id, and the passage that was found first - read_document reads around it. */
    public record FoundDocument(Long documentId, String passage) {
    }

    /** A changing tool call of a connected software, not run: the user decides first. */
    public record PendingCall(McpTools.Offered offered, ToolExecutionRequest request) {
    }

    public ToolContext(Tenant tenant, Asker asker, AiModel model, @Nullable Long userId, KnowledgeScope knowledge,
                       DataSource dataSource, @Nullable Long conversationId, boolean conversationHasFiles,
                       int searchResults, Files files) {
        this.tenant = tenant;
        this.asker = asker;
        this.model = model;
        this.userId = userId;
        this.knowledge = knowledge;
        this.dataSource = dataSource;
        this.conversationId = conversationId;
        this.conversationHasFiles = conversationHasFiles;
        this.searchResults = searchResults;
        this.files = files;
    }

    public Tenant tenant() {
        return tenant;
    }

    public Asker asker() {
        return asker;
    }

    /** The chat model that answers - what it reads as it is decides how read_file returns a file. */
    public AiModel model() {
        return model;
    }

    /** The asking user; null for the client software. */
    public @Nullable Long userId() {
        return userId;
    }

    /** The documents the asker finds: the company knowledge and its areas (docs/architecture/ai-features.md "Knowledge areas"). */
    public KnowledgeScope knowledge() {
        return knowledge;
    }

    /** The tenant's database. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** The conversation the question continues; null for a new one. */
    public @Nullable Long conversationId() {
        return conversationId;
    }

    public boolean conversationHasFiles() {
        return conversationHasFiles;
    }

    /** The passages one search returns - the tenant's setting (or its template's). */
    public int searchResults() {
        return searchResults;
    }

    public Files files() {
        return files;
    }

    /** Whether a created or returned file goes anywhere - kept or sent with the answer. */
    public boolean takesFiles() {
        return files != Files.NONE;
    }

    /**
     * What the model is told of a file it created or a tool returned. The models otherwise like to add a made-up link
     * (seen: "[...](sandbox:/name.docx)"), and with ask_assistant to write the file's content out again.
     */
    public String fileNote(String fileName) {
        String created = "The file \"" + fileName + "\" was created. ";
        return files == Files.DELIVERED
                ? created + "It goes to the asker with your answer - do not repeat its content, write no link or path to it."
                : created + "The application shows its download below the answer - do not write a link or path to it.";
    }

    public List<ChatFileService.PendingFile> createdFiles() {
        return createdFiles;
    }

    /** The created files that are no image - limited per answer on their own; images by hand_out_file's limit. */
    public long createdFilesExceptImages() {
        return createdFiles.stream().filter(file -> !file.contentType().startsWith("image/")).count();
    }

    public long createdImages() {
        return createdFiles.size() - createdFilesExceptImages();
    }

    public Set<String> sources() {
        return sources;
    }

    public Set<Long> excludedConnections() {
        return excludedConnections;
    }

    public void excludedConnections(Set<Long> connectionIds) {
        this.excludedConnections = Set.copyOf(connectionIds);
    }

    public @Nullable List<Long> exclusionToKeep() {
        return exclusionToKeep;
    }

    public void exclusionToKeep(@Nullable List<Long> connectionIds) {
        this.exclusionToKeep = connectionIds == null ? null : List.copyOf(connectionIds);
    }

    public List<McpTools.Offered> softwareTools() {
        return softwareTools;
    }

    void softwareTools(List<McpTools.Offered> tools) {
        this.softwareTools = List.copyOf(tools);
    }

    public void stopWhen(BooleanSupplier stop) {
        this.stopWhen = stop;
    }

    public boolean stopRequested() {
        return stopWhen.getAsBoolean();
    }

    public List<Long> taskConnections() {
        return taskConnections;
    }

    public void taskConnections(List<Long> connectionIds) {
        this.taskConnections = List.copyOf(connectionIds);
    }

    public @Nullable Set<String> onlyTools() {
        return onlyTools;
    }

    public void onlyTools(Set<String> names) {
        this.onlyTools = Set.copyOf(names);
    }

    /** Whether a tool of this name may be offered and called in this answer. */
    public boolean allows(String toolName) {
        return onlyTools == null || onlyTools.contains(toolName);
    }

    /** One round's call, as the usage recorded it. */
    public void count(CallTokens tokens, BigDecimal cost) {
        spent.add(tokens, cost);
    }

    /** Counts the answer's calls into this one - a task's step, which keeps it when the answer fails. */
    public void countInto(Spent into) {
        spent = into;
    }

    public Spent spent() {
        return spent;
    }

    public List<ExploredFile> exploredFiles() {
        return exploredFiles;
    }

    public void exploredFiles(List<ExploredFile> files) {
        this.exploredFiles = List.copyOf(files);
    }

    /**
     * Notes a document a search found and returns the name the model sees it under: its own, or "name (2)" when another
     * document of that name was found first (two pushes of "angebot.pdf", one path in two areas) - read_document then
     * reads the one meant. Its first (best) passage is kept.
     */
    public String documentFound(String name, Long documentId, String passage) {
        String shown = name;
        for (int n = 2; foundDocuments.containsKey(shown) && !foundDocuments.get(shown).documentId().equals(documentId); n++) {
            shown = name + " (" + n + ")";
        }
        foundDocuments.putIfAbsent(shown, new FoundDocument(documentId, passage));
        return shown;
    }

    public @Nullable FoundDocument foundDocument(String name) {
        return foundDocuments.get(name);
    }

    public int documentCharactersRead() {
        return documentCharactersRead;
    }

    public void documentRead(int characters) {
        documentCharactersRead += characters;
    }

    public int imageCalls() {
        return imageCalls;
    }

    public void imageCalled() {
        imageCalls++;
    }

    public boolean searchesWeb() {
        return searchesWeb;
    }

    public void searchesWeb(boolean searches) {
        this.searchesWeb = searches;
    }

    /** The API key or MCP key of the question, by the asker; null for a user or a task. */
    public @Nullable Long keyId() {
        return keyId;
    }

    public void keyId(@Nullable Long keyId) {
        this.keyId = keyId;
    }

    public boolean documentsSearched() {
        return documentsSearched;
    }

    public void documentsSearched(boolean searched) {
        this.documentsSearched = searched;
    }

    public int webSearches() {
        return webSearches;
    }

    public void webSearched() {
        webSearches++;
    }

    public int imagesViewed() {
        return imagesViewed;
    }

    public void imagesViewed(int count) {
        imagesViewed += count;
    }

    public List<SentFile> sentFiles() {
        return sentFiles;
    }

    public void sentFiles(List<SentFile> files) {
        this.sentFiles = List.copyOf(files);
    }

    public boolean examinesVideo() {
        return examinesVideo;
    }

    public void examinesVideo(boolean examines) {
        this.examinesVideo = examines;
    }

    public @Nullable SandboxSession sandbox() {
        return sandbox;
    }

    public void sandbox(SandboxSession session) {
        this.sandbox = session;
    }

    /** Ends the answer's sandbox session, if its code opened one - the instance deletes everything of it. */
    public void closeSandbox() {
        if (sandbox != null) {
            sandbox.close();
            sandbox = null;
        }
    }

    public @Nullable PendingCall pendingCall() {
        return pendingCall;
    }

    public void pendingCall(PendingCall call) {
        this.pendingCall = call;
    }
}
