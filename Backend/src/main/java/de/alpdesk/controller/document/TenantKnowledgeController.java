package de.alpdesk.controller.document;

import de.alpdesk.dto.ai.EmbeddingModelRequest;
import de.alpdesk.dto.ai.RerankingModelRequest;
import de.alpdesk.dto.document.ArchiveImportResponse;
import de.alpdesk.dto.document.DeletedDocumentsResponse;
import de.alpdesk.dto.document.DocumentAreaRequest;
import de.alpdesk.dto.document.DocumentResponse;
import de.alpdesk.dto.document.EmbeddedAgainResponse;
import de.alpdesk.dto.document.SearchLanguageRequest;
import de.alpdesk.dto.tenant.TenantResponse;
import de.alpdesk.entities.document.Document;
import de.alpdesk.security.Roles;
import de.alpdesk.services.document.DocumentService;
import de.alpdesk.services.document.KnowledgeAreaService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;
import java.util.Map;

/**
 * A tenant's company knowledge, kept by the admin - like its tenant admins do (DocumentController): the documents every
 * user of the tenant finds in the chat without seeing them, and the embedding model they are embedded with.
 */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantKnowledgeController {

    private final DocumentService service;
    private final KnowledgeAreaService areas;

    public TenantKnowledgeController(DocumentService service, KnowledgeAreaService areas) {
        this.service = service;
        this.areas = areas;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/documents")
    public List<DocumentResponse> list(@PathVariable Long tenantId) {
        List<Document> documents = service.findAll(tenantId);
        Map<Long, String> areaNames = areas.namesById(tenantId);
        return documents.stream().map(document -> DocumentResponse.from(document, areaNames)).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/documents")
    public HttpResponse<DocumentResponse> upload(@PathVariable Long tenantId, @Part("file") CompletedFileUpload file,
                                                 @Nullable @Part("areaId") Long areaId) {
        return HttpResponse.created(DocumentResponse.from(service.upload(tenantId, null, areaId,
                file.getFilename(), DocumentUploads.content(file)), areas.namesById(tenantId)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/documents/archive")
    public ArchiveImportResponse importArchive(@PathVariable Long tenantId, @Part("file") CompletedFileUpload file,
                                               @Nullable @Part("areaId") Long areaId) {
        return ArchiveImportResponse.from(DocumentUploads.withArchive(file,
                zip -> service.importArchive(tenantId, null, areaId, zip)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Post("/documents/{id}/retry")
    public DocumentResponse retry(@PathVariable Long tenantId, @PathVariable Long id) {
        return DocumentResponse.from(service.retry(tenantId, id), areas.namesById(tenantId));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/documents/{id}/area")
    public DocumentResponse moveToArea(@PathVariable Long tenantId, @PathVariable Long id,
                                       @Body DocumentAreaRequest request) {
        return DocumentResponse.from(service.moveToArea(tenantId, id, request.areaId()), areas.namesById(tenantId));
    }

    /** The same as the tenant admins' - every document embedded again, at the cost of uploading it anew. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/documents/embed-again")
    public EmbeddedAgainResponse embedAllAgain(@PathVariable Long tenantId) {
        return new EmbeddedAgainResponse(service.embedAllAgain(tenantId));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Delete("/documents/uploads")
    public DeletedDocumentsResponse deleteUploads(@PathVariable Long tenantId, @Nullable @QueryValue String area) {
        return new DeletedDocumentsResponse(service.deleteUploads(tenantId, area));
    }

    @Delete("/documents/{id}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long id) {
        return service.delete(tenantId, id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** The same choice as the tenant admins' - changing it embeds every document again. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/embedding-model")
    public TenantResponse setEmbeddingModel(@PathVariable Long tenantId, @Body EmbeddingModelRequest request) {
        return TenantResponse.from(service.setEmbeddingModel(tenantId, request.modelId()));
    }

    /** The same choice as the tenant admins' - reranking on (a model's id) or off (null), nothing embedded again. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/reranking-model")
    public TenantResponse setRerankingModel(@PathVariable Long tenantId, @Body RerankingModelRequest request) {
        return TenantResponse.from(service.setRerankingModel(tenantId, request.modelId()));
    }

    /** The same choice as the tenant admins' - builds the full-text search again, embeds nothing. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/search-language")
    public TenantResponse setSearchLanguage(@PathVariable Long tenantId, @Body SearchLanguageRequest request) {
        return TenantResponse.from(service.setSearchLanguage(tenantId, request.language()));
    }
}
