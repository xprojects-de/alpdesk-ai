package de.alpdesk.controller.api.v1;

import de.alpdesk.dto.common.ErrorResponse;
import de.alpdesk.controller.document.DocumentUploads;
import de.alpdesk.dto.document.DocumentResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.document.DocumentService;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.document.KnowledgeScope;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * The client software's way to the company's knowledge - the documents every user finds in the chat, next to the ones
 * the admins upload. Embedded automatically; with an externalId a push replaces its earlier version. Decided 6 October
 * 2026: upload, list and delete - the text extraction itself is not offered. A key sees and writes the company knowledge
 * and the knowledge areas assigned to it (decided 9 October 2026).
 */
@Secured(Roles.TENANT)
@Controller("/api/v1/documents")
@ExecuteOn(TaskExecutors.BLOCKING)
@Tag(name = "API v1")
public class DocumentApiController {

    private final DocumentService service;
    private final KnowledgeAreaService areas;

    public DocumentApiController(DocumentService service, KnowledgeAreaService areas) {
        this.service = service;
        this.areas = areas;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    @Operation(summary = "The tenant's documents the key finds - the company knowledge and its knowledge areas - newest "
            + "first, with their embedding status", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The documents", content = @Content(schema = @Schema(implementation = DocumentResponse.class)))
    public List<DocumentResponse> list(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        Map<Long, String> areaNames = areas.namesById(tenantId);
        return service.findAll(tenantId, scope(authentication)).stream()
                .map(document -> DocumentResponse.from(document, areaNames)).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post
    @Operation(summary = "Upload a document (multipart field \"file\", up to 50 MB: PDF, DOCX, ODT, RTF, TXT, Markdown, "
            + "HTML, PPTX, XLSX, ODS, CSV) - it is embedded in the background (status PENDING, then READY or FAILED). "
            + "With the optional field \"externalId\" (your own key, up to 200 characters) a document you pushed with "
            + "the same key is replaced instead of a second one being added - and left as it is if its text is unchanged "
            + "(not embedded again). With the optional field \"area\" (a knowledge area's name the key is assigned to) "
            + "only the users and keys of that area find it; empty (\"\") it is company knowledge everyone finds. "
            + "Without the field a new document is company knowledge and a known one keeps its area; with another "
            + "area a known document moves there", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "201", description = "Stored as a new document, waiting to be embedded", content = @Content(schema = @Schema(implementation = DocumentResponse.class)))
    @ApiResponse(responseCode = "200", description = "The document with this externalId: replaced and waiting to be embedded again, or unchanged", content = @Content(schema = @Schema(implementation = DocumentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Empty, unreadable or password-protected document, one without text, an invalid externalId, an unknown area or one the key is not assigned to", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "No embedding model chosen, no API key for its provider, or the document with this externalId is in an area the key is not assigned to", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "413", description = "The document exceeds the size limit")
    @ApiResponse(responseCode = "415", description = "The file type is not supported", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public HttpResponse<DocumentResponse> upload(Authentication authentication, @Part("file") CompletedFileUpload file,
                                                 @Nullable @Part("externalId") String externalId,
                                                 @Nullable @Part("area") String area) {
        Long tenantId = TenantContext.tenantId(authentication);
        DocumentService.Pushed pushed = service.push(tenantId, scope(authentication), area, externalId,
                file.getFilename(), DocumentUploads.content(file));
        DocumentResponse body = DocumentResponse.from(pushed.document(), areas.namesById(tenantId));
        return pushed.outcome() == DocumentService.PushOutcome.CREATED ? HttpResponse.created(body) : HttpResponse.ok(body);
    }

    @Delete
    @Operation(summary = "Delete the document pushed with this externalId - the chat no longer finds it", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "No or an invalid externalId")
    @ApiResponse(responseCode = "404", description = "No document of the tenant with this externalId the key finds")
    public HttpResponse<?> deleteByExternalId(Authentication authentication, @QueryValue @Nullable String externalId) {
        return service.deleteByExternalId(TenantContext.tenantId(authentication), scope(authentication), externalId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Delete("/{id}")
    @Operation(summary = "Delete a document - the chat no longer finds it", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such document of the tenant the key finds")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long id) {
        return service.delete(TenantContext.tenantId(authentication), scope(authentication), id)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** The company knowledge and the key's areas - what it sees and writes. */
    private KnowledgeScope scope(Authentication authentication) {
        return areas.scopeOfApiKey(TenantContext.apiKeyId(authentication));
    }
}
