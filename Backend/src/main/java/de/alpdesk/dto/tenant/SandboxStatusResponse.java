package de.alpdesk.dto.tenant;

import de.alpdesk.services.tenant.TenantService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Serdeable
@Schema(description = "The admin's check of a tenant's sandbox instances")
public record SandboxStatusResponse(
        List<Instance> instances,
        @Schema(description = "Whether the instances answering carry the same README of their own programs (or all none) "
                + "- the model gets it only then")
        boolean extrasAgree) {

    @Serdeable
    public record Instance(
            String url,
            @Schema(description = "reachable, busy, token-refused, unreachable or unusable (it takes no session until "
                    + "it is started again - its log says why)")
            String status,
            @Schema(description = "Whether the instance is the video image (it examines videos); null when it did not answer")
            @Nullable Boolean video,
            @Schema(description = "Whether the instance is an image built on ours with a README of its own programs; null "
                    + "when it did not answer")
            @Nullable Boolean extras) {
    }

    public static SandboxStatusResponse from(TenantService.SandboxChecks checks) {
        return new SandboxStatusResponse(checks.instances().entrySet().stream()
                .map(entry -> new Instance(entry.getKey(),
                        entry.getValue().status().name().toLowerCase().replace('_', '-'), entry.getValue().video(),
                        entry.getValue().extras()))
                .toList(), checks.extrasAgree());
    }
}
