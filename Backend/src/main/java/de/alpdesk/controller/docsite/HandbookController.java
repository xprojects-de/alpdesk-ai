package de.alpdesk.controller.docsite;

import de.alpdesk.dto.docsite.DocSiteAddressResponse;
import de.alpdesk.dto.docsite.DocSiteResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.docsite.DocSiteService;
import de.alpdesk.services.document.KnowledgeAreaService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;
import java.util.Map;

/** The handbook of a tenant user: the websites it reads, and the address to read one in an iframe. */
@Secured(Roles.TENANT_USER)
@Hidden
@Controller("/tenant/doc-sites")
@ExecuteOn(TaskExecutors.BLOCKING)
public class HandbookController {

    private final DocSiteService service;
    private final KnowledgeAreaService areas;

    public HandbookController(DocSiteService service, KnowledgeAreaService areas) {
        this.service = service;
        this.areas = areas;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<DocSiteResponse> list(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        Map<Long, String> areaNames = areas.namesById(tenantId);
        return service.findReadable(tenantId, TenantContext.userId(authentication)).stream()
                .map(site -> DocSiteResponse.from(site, areaNames)).toList();
    }

    /** 404 for a website of an area the user is not assigned to, like an unknown one. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{id}/address")
    public DocSiteAddressResponse address(Authentication authentication, @PathVariable Long id) {
        return new DocSiteAddressResponse(service.addressForUser(TenantContext.tenantId(authentication),
                TenantContext.userId(authentication), id));
    }
}
