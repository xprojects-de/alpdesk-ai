# See README.md in this folder. Two images from one runtime:
#
#   --target release      production: the jar of a GitHub release (build.yml ran the tests, checked the jar's
#                         contents and started it), copied in, nothing built here. The build context is the folder
#                         holding the jar (podman/release, kept out of the other context by .dockerignore):
#                         podman build -f podman/Dockerfile --target release --build-arg RELEASE_JAR=<jar> podman/release
#   --target from-source  the local test stack (docker-compose.yml): built from the working tree (context Backend/),
#                         without the tests.
#                         The Angular frontend must already be built into src/main/resources/public
#                         (Frontend: `npm run deploy`, then Backend: `./gradlew copyFrontend`).

FROM eclipse-temurin:25-jdk-resolute AS builder

WORKDIR /home/app

COPY . .

RUN ./gradlew clean optimizedJitJarAll -x test --no-daemon

# RUNTIME (shared by both targets)

# Ubuntu 26.04 (resolute), like the sandbox.
FROM eclipse-temurin:25-jre-resolute AS runtime

WORKDIR /home/app

LABEL maintainer="X-Projects"

# No credentials are baked into the image: APP_USERNAME, APP_PASSWORD,
# JWT_GENERATOR_SIGNATURE_SECRET and the DATASOURCES_DEFAULT_* database settings are passed in at
# runtime (see docker-compose*.yml). State: data in PostgreSQL, the chat's files in the file storage volume below
# (APP_STORAGE_PATH). ffmpeg converts uploaded audio and video into the small copy the AI models are sent
# (services.chat.MediaConverter).
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
# A fixed, numeric user: Kubernetes' runAsNonRoot can only check a numeric one, and 999 is what useradd -r gave before -
# a volume "files" of an existing installation stays writable.
RUN groupadd -r -g 999 appuser && useradd --no-log-init -r -u 999 -g appuser appuser \
    && mkdir -p /home/app/files \
    && chown -R appuser:appuser /home/app

# micronaut.server.port in application.properties
EXPOSE 19100
# The chat's files - see app.storage.* in application.properties
ENV APP_STORAGE_PATH=/home/app/files
VOLUME ["/home/app/files"]

# The company's own certificate authorities: with USE_SYSTEM_CA_CERTS=1 the base image's entrypoint adds every
# /certificates/*.crt to a copy of the JVM's truststore at each start (as the non-root user in /tmp) - without it, it
# only starts java. /certificates points into the volume, so the certificates are copied in like the logo and survive
# an update, and an installation without them needs no mount. A folder of the server is mounted on
# /home/app/files/certificates - a mount on the link itself fails (public/README.md "Own model servers").
RUN ln -s /home/app/files/certificates /certificates

ENTRYPOINT ["/__cacert_entrypoint.sh", "java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app.jar"]

# PRODUCTION: the released jar

FROM runtime AS release

# The release asset's file name in the build context (podman/release), e.g. alpdesk-ai-v1.0.0.jar.
ARG RELEASE_JAR
# Without the argument COPY would take "/app.jar" alone and copy the context - the build must stop instead.
RUN test -n "${RELEASE_JAR}" || { echo "--build-arg RELEASE_JAR=<the release jar in the build context> is required"; exit 1; }
COPY --chown=999:999 ${RELEASE_JAR} /app.jar
RUN test -f /app.jar || { echo "${RELEASE_JAR} is not a file"; exit 1; }
USER 999:999

# LOCAL TEST STACK: built from the working tree (the default target - last in this file)

FROM runtime AS from-source

COPY --from=builder --chown=999:999 /home/app/build/libs/alpdesk-ai.jar /app.jar
USER 999:999
