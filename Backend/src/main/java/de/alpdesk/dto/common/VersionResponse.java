package de.alpdesk.dto.common;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class VersionResponse {
    private final String version;
    private final String application;

    public VersionResponse(String version, String application) {
        this.version = version;
        this.application = application;
    }

    @SuppressWarnings("unused")
    public String getVersion() {
        return version;
    }

    @SuppressWarnings("unused")
    public String getApplication() {
        return application;
    }
}

