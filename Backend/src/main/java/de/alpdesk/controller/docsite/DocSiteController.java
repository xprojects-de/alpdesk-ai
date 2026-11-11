package de.alpdesk.controller.docsite;

import de.alpdesk.controller.document.DocumentUploads;
import de.alpdesk.dto.docsite.DocSiteAddressResponse;
import de.alpdesk.dto.docsite.DocSiteResponse;
import de.alpdesk.dto.docsite.DocSitesResponse;
import de.alpdesk.dto.docsite.DocSiteUploadResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.docsite.DocSiteService;
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
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.Map;

/** The handbook's websites of the tenant admin's own tenant - like the admin's (DocSiteAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/doc-sites")
@ExecuteOn(TaskExecutors.BLOCKING)
public class DocSiteController {

    private final DocSiteService service;
    private final KnowledgeAreaService areas;

    public DocSiteController(DocSiteService service, KnowledgeAreaService areas) {
        this.service = service;
        this.areas = areas;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public DocSitesResponse list(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        Map<Long, String> areaNames = areas.namesById(tenantId);
        return new DocSitesResponse(service.findAll(tenantId).stream()
                .map(site -> DocSiteResponse.from(site, areaNames)).toList(), service.maxZipBytes());
    }

    /** The ZIP replaces the website of the whole tenant or of an area (areaId). */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post
    public DocSiteUploadResponse upload(Authentication authentication, @Part("file") CompletedFileUpload file,
                                        @Nullable @Part("areaId") Long areaId) {
        Long tenantId = TenantContext.tenantId(authentication);
        DocSiteService.Upload upload = DocumentUploads.withArchive(file,
                zip -> service.replace(tenantId, areaId, file.getSize(), zip));
        return DocSiteUploadResponse.from(upload, areas.namesById(tenantId));
    }

    @Delete("/{id}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long id) {
        return service.delete(TenantContext.tenantId(authentication), id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** To look at a website - a tenant admin reads every one. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{id}/address")
    public DocSiteAddressResponse address(Authentication authentication, @PathVariable Long id) {
        return new DocSiteAddressResponse(service.address(TenantContext.tenantId(authentication), id,
                KnowledgeScope.everything()));
    }
}
