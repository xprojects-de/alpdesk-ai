package de.alpdesk.controller.filestore;

import de.alpdesk.dto.filestore.FileStoreCheckResponse;
import de.alpdesk.dto.filestore.FileStoreFoldersResponse;
import de.alpdesk.dto.filestore.FileStoreRequest;
import de.alpdesk.dto.filestore.FileStoreResponse;
import de.alpdesk.dto.filestore.FingerprintRequest;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.filestore.FileStoreService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The file stores of the tenant admin's own tenant - like the admin's (FileStoreAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/file-stores")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminFileStoreController {

    private final FileStoreService service;

    public TenantAdminFileStoreController(FileStoreService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<FileStoreResponse> list(Authentication authentication) {
        return service.list(tenantId(authentication));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<FileStoreResponse> create(Authentication authentication, @Body FileStoreRequest request) {
        return HttpResponse.created(service.create(tenantId(authentication), request));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{storeId}")
    public HttpResponse<FileStoreResponse> update(Authentication authentication, @PathVariable Long storeId,
                                                  @Body FileStoreRequest request) {
        return service.update(tenantId(authentication), storeId, request).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{storeId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long storeId) {
        return service.delete(tenantId(authentication), storeId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** Connects to the server - a failure is the answer's status, not an error. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{storeId}/check")
    public HttpResponse<FileStoreCheckResponse> check(Authentication authentication, @PathVariable Long storeId) {
        return service.check(tenantId(authentication), storeId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{storeId}/fingerprint")
    public HttpResponse<FileStoreResponse> confirmFingerprint(Authentication authentication, @PathVariable Long storeId,
                                                              @Body FingerprintRequest request) {
        return service.confirmFingerprint(tenantId(authentication), storeId, request.fingerprint())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** The subfolders of a folder of the store, to choose one - path relative to the store's folder. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{storeId}/folders")
    public HttpResponse<FileStoreFoldersResponse> folders(Authentication authentication, @PathVariable Long storeId,
                                                          @Nullable @QueryValue String path) {
        return service.folders(tenantId(authentication), storeId, path).map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    private static Long tenantId(Authentication authentication) {
        return TenantContext.tenantId(authentication);
    }
}
