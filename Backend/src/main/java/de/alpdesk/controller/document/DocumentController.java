package de.alpdesk.controller.document;

import de.alpdesk.dto.ai.AiSettingsResponse;
import de.alpdesk.dto.ai.EmbeddingModelRequest;
import de.alpdesk.dto.ai.RerankingModelRequest;
import de.alpdesk.dto.document.ArchiveImportResponse;
import de.alpdesk.dto.document.DeletedDocumentsResponse;
import de.alpdesk.dto.document.DocumentAreaRequest;
import de.alpdesk.dto.document.DocumentResponse;
import de.alpdesk.dto.document.EmbeddedAgainResponse;
import de.alpdesk.dto.document.SearchLanguageRequest;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
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
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;
import java.util.Map;

/**
 * The company's knowledge of the tenant admin's own tenant - like the admin's (TenantKnowledgeController): upload
 * (embedded in the background) into the company knowledge or an area, list, retry, move, delete, and the embedding
 * model. Users only find it in the chat.
 */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin")
@ExecuteOn(TaskExecutors.BLOCKING)
public class DocumentController {

    private final DocumentService service;
    private final KnowledgeAreaService areas;

    public DocumentController(DocumentService service, KnowledgeAreaService areas) {
        this.service = service;
        this.areas = areas;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/documents")
    public List<DocumentResponse> list(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        Map<Long, String> areaNames = areas.namesById(tenantId);
        return service.findAll(tenantId).stream().map(document -> DocumentResponse.from(document, areaNames)).toList();
    }

    /** areaId: the area it belongs to; left out: the company knowledge. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/documents")
    public HttpResponse<DocumentResponse> upload(Authentication authentication, @Part("file") CompletedFileUpload file,
                                                 @Nullable @Part("areaId") Long areaId) {
        Long tenantId = TenantContext.tenantId(authentication);
        return HttpResponse.created(DocumentResponse.from(service.upload(tenantId, TenantContext.userId(authentication),
                areaId, file.getFilename(), DocumentUploads.content(file)), areas.namesById(tenantId)));
    }

    /**
     * A ZIP archive of documents into the company knowledge or an area (areaId), its folders kept - imported again,
     * changed files are replaced (DocumentService).
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/documents/archive")
    public ArchiveImportResponse importArchive(Authentication authentication, @Part("file") CompletedFileUpload file,
                                               @Nullable @Part("areaId") Long areaId) {
        return ArchiveImportResponse.from(DocumentUploads.withArchive(file, zip -> service.importArchive(
                TenantContext.tenantId(authentication), TenantContext.userId(authentication), areaId, zip)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Post("/documents/{id}/retry")
    public DocumentResponse retry(Authentication authentication, @PathVariable Long id) {
        Long tenantId = TenantContext.tenantId(authentication);
        return DocumentResponse.from(service.retry(tenantId, id), areas.namesById(tenantId));
    }

    /** Moves the document to an area, or to the company knowledge (areaId null) - nothing is embedded again. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/documents/{id}/area")
    public DocumentResponse moveToArea(Authentication authentication, @PathVariable Long id,
                                       @Body DocumentAreaRequest request) {
        Long tenantId = TenantContext.tenantId(authentication);
        return DocumentResponse.from(service.moveToArea(tenantId, id, request.areaId()), areas.namesById(tenantId));
    }

    /** Embeds every document again with the tenant's model - see DocumentService.embedAllAgain. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/documents/embed-again")
    public EmbeddedAgainResponse embedAllAgain(Authentication authentication) {
        return new EmbeddedAgainResponse(service.embedAllAgain(TenantContext.tenantId(authentication)));
    }

    /**
     * Deletes every document uploaded in the frontend into the company knowledge (area=company) or an area (its id) -
     * before its ZIP archive is imported anew. The client software's documents stay.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Delete("/documents/uploads")
    public DeletedDocumentsResponse deleteUploads(Authentication authentication, @Nullable @QueryValue String area) {
        return new DeletedDocumentsResponse(service.deleteUploads(TenantContext.tenantId(authentication), area));
    }

    @Delete("/documents/{id}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long id) {
        return service.delete(TenantContext.tenantId(authentication), id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** Changing the model embeds every document again - see DocumentService.setEmbeddingModel. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/embedding-model")
    public AiSettingsResponse setEmbeddingModel(Authentication authentication, @Body EmbeddingModelRequest request) {
        Tenant tenant = service.setEmbeddingModel(TenantContext.tenantId(authentication), request.modelId());
        return AiSettingsResponse.from(tenant);
    }

    /** Switches the reranker of the knowledge search on (a model's id) or off (null) - see DocumentService.setRerankingModel. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/reranking-model")
    public AiSettingsResponse setRerankingModel(Authentication authentication, @Body RerankingModelRequest request) {
        return AiSettingsResponse.from(service.setRerankingModel(TenantContext.tenantId(authentication), request.modelId()));
    }

    /** Builds the full-text search again for the language, embeds nothing - see DocumentService.setSearchLanguage. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/search-language")
    public AiSettingsResponse setSearchLanguage(Authentication authentication, @Body SearchLanguageRequest request) {
        Tenant tenant = service.setSearchLanguage(TenantContext.tenantId(authentication), request.language());
        return AiSettingsResponse.from(tenant);
    }
}
