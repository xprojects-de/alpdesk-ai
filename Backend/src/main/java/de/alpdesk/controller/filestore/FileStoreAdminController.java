package de.alpdesk.controller.filestore;

import de.alpdesk.dto.filestore.FileStoreCheckResponse;
import de.alpdesk.dto.filestore.FileStoreFoldersResponse;
import de.alpdesk.dto.filestore.FileStoreRequest;
import de.alpdesk.dto.filestore.FileStoreResponse;
import de.alpdesk.dto.filestore.FingerprintRequest;
import de.alpdesk.security.Roles;
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
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** A tenant's file stores for the admin - the tenant admins keep their own (TenantAdminFileStoreController). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/file-stores")
@ExecuteOn(TaskExecutors.BLOCKING)
public class FileStoreAdminController {

    private final FileStoreService service;

    public FileStoreAdminController(FileStoreService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<FileStoreResponse> list(@PathVariable Long tenantId) {
        return service.list(tenantId);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<FileStoreResponse> create(@PathVariable Long tenantId, @Body FileStoreRequest request) {
        return HttpResponse.created(service.create(tenantId, request));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{storeId}")
    public HttpResponse<FileStoreResponse> update(@PathVariable Long tenantId, @PathVariable Long storeId,
                                                  @Body FileStoreRequest request) {
        return service.update(tenantId, storeId, request).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{storeId}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long storeId) {
        return service.delete(tenantId, storeId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** Connects to the server - a failure is the answer's status, not an error. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{storeId}/check")
    public HttpResponse<FileStoreCheckResponse> check(@PathVariable Long tenantId, @PathVariable Long storeId) {
        return service.check(tenantId, storeId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{storeId}/fingerprint")
    public HttpResponse<FileStoreResponse> confirmFingerprint(@PathVariable Long tenantId, @PathVariable Long storeId,
                                                              @Body FingerprintRequest request) {
        return service.confirmFingerprint(tenantId, storeId, request.fingerprint())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{storeId}/folders")
    public HttpResponse<FileStoreFoldersResponse> folders(@PathVariable Long tenantId, @PathVariable Long storeId,
                                                          @Nullable @QueryValue String path) {
        return service.folders(tenantId, storeId, path).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }
}
