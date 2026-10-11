# Third-party libraries and their licenses

Alpdesk AI itself is under the [MIT license](LICENSE). The released jar, the container image and the frontend bundle
include the libraries listed below; their licenses continue to apply to their part. None of them is a strong copyleft
license that extends to the project's own code: the copyleft ones are weak copyleft (LGPL, MPL, CDDL, EPL), used as
unmodified libraries, or offer a permissive option.

The licenses were read out of the resolved artifacts - POM metadata, `Bundle-License` in the jar manifests,
`META-INF/LICENSE` files and source headers for the backend, the `license` field of each installed package for the
frontend - not assumed. As of: backend `runtimeClasspath` and frontend `dependencies` of 10 October 2026.

## Backend (Java, shipped in the jar)

### Apache License 2.0

Most of the stack. Apache-2.0 asks for the license and the NOTICE contents to be passed on - this file together with
[`NOTICE`](NOTICE) does that.

| Component | Role |
|---|---|
| `io.micronaut*:*` (core, http, data, security, serde, sql, flyway, reactor, jsonschema, mcp) | Application framework, MCP server and client |
| `dev.langchain4j:*` (core, anthropic, open-ai, google-ai-gemini, ollama, http-client, pgvector) | AI provider clients, vector store |
| `org.apache.tika:*` (core and parser modules) | Text extraction from documents |
| `org.apache.poi:*`, `org.apache.xmlbeans:xmlbeans` | Office formats |
| `org.apache.pdfbox:*` (pdfbox, fontbox, pdfbox-io, xmpbox, pdfbox-tools, jempbox, jbig2-imageio), `de.rototor.pdfbox:graphics2d` | PDF reading and rendering |
| `org.apache.commons:*`, `commons-io`, `commons-codec`, `commons-logging` | Utilities |
| `org.apache.james:apache-mime4j-*`, `org.apache.opennlp:opennlp-tools`, `org.apache.logging.log4j:log4j-api`, `log4j-to-slf4j` | Tika's parsing, Log4j bridge |
| `org.flywaydb:flyway-core`, `flyway-database-postgresql` (Community edition) | Database migrations |
| `io.netty:*`, `io.netty.contrib:netty-codec-multipart-core` | Network layer of the HTTP server |
| `com.fasterxml.jackson.core:*`, `tools.jackson.*` | JSON, YAML |
| `com.nimbusds:nimbus-jose-jwt` | JWT |
| `com.zaxxer:HikariCP`, `com.zaxxer:SparseBitSet`, `io.projectreactor:reactor-core`, `io.smallrye.reactive:mutiny-zero`, `com.networknt:json-schema-validator`, `com.ethlo.time:itu`, `org.snakeyaml:snakeyaml-engine` | Runtime support |
| `org.jspecify:jspecify`, `jakarta.inject:jakarta.inject-api`, `jakarta.validation:jakarta.validation-api`, `org.jetbrains:annotations`, `info.picocli:picocli` | Standard APIs and annotations |
| `com.drewnoakes:metadata-extractor`, `com.epam:parso`, `com.pff:java-libpst`, `com.healthmarketscience.jackcess:jackcess`, `jackcess-encrypt`, `com.rometools:rome`, `rome-utils`, `org.gagravarr:vorbis-java-*`, `org.netpreserve:jwarc` | Dependencies of Tika's parsers |

### MIT and similar

| Component | License | Role |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp-core` | MIT | MCP Java SDK |
| `com.pgvector:pgvector` | MIT | pgvector types for JDBC |
| `org.slf4j:slf4j-api`, `jcl-over-slf4j` | MIT | Logging API |
| `org.jsoup:jsoup` | MIT | HTML clean-up |
| `com.knuddels:jtokkit`, `com.googlecode.plist:dd-plist`, `org.checkerframework:checker-qual`, `org.brotli:dec` | MIT | Tokenizer and support libraries |
| `org.reactivestreams:reactive-streams` | MIT-0 | Reactive Streams API |
| `org.bouncycastle:bcprov-jdk18on`, `bcpkix`, `bcutil`, `bcjmail` | Bouncy Castle Licence (MIT-style) | Cryptography for Tika |
| `org.tukaani:xz` | 0BSD | Compression |
| `org.jdom:jdom2` | JDOM licence (Apache-1.1-style) | XML for Tika |

### BSD

| Component | License | Role |
|---|---|---|
| `org.postgresql:postgresql` (with the shaded `com.ongres.scram`, `stringprep`) | BSD-2-Clause | PostgreSQL JDBC driver |
| `com.vladsch.flexmark:*` | BSD-2-Clause | Markdown |
| `com.adobe.xmp:xmpcore`, `org.ow2.asm:asm`, `com.github.virtuald:curvesapi`, `org.tallison:jmatio`, `com.github.jai-imageio:jai-imageio-core` | BSD-3-Clause | Dependencies of Tika and POI |
| `jakarta.xml.bind:jakarta.xml.bind-api`, `jakarta.activation:jakarta.activation-api`, `org.glassfish.jaxb:*`, `com.sun.istack:istack-commons-runtime`, `org.eclipse.angus:angus-activation` | EDL-1.0 (BSD-3-Clause) | JAXB for POI and Tika |

### Weak copyleft and dual licenses

These are used as unmodified libraries. Where a component offers a choice, the option named applies here; the others
are not used and so do not extend to the project's own code.

| Component | License | Applies here | Role |
|---|---|---|---|
| `io.github.openhtmltopdf:openhtmltopdf-core`, `openhtmltopdf-pdfbox` | LGPL-2.1-or-later | LGPL-2.1 - a separate, replaceable library; source at https://github.com/openhtmltopdf/openhtmltopdf | Renders the PDF files the chat creates |
| `ch.qos.logback:logback-classic`, `logback-core` | EPL-2.0 **or** LGPL-2.1 | EPL-2.0 | Logging |
| `jakarta.annotation:jakarta.annotation-api`, `jakarta.transaction:jakarta.transaction-api` | EPL-2.0 **or** GPL-2.0 with Classpath exception | EPL-2.0 | Standard APIs |
| `jakarta.persistence:jakarta.persistence-api` | EPL-2.0 **or** EDL-1.0 | EDL-1.0 | Standard API |
| `com.github.albfernandez:juniversalchardet` | MPL-1.1 **or** GPL-3.0 **or** LGPL-3.0 | MPL-1.1 - source at https://github.com/albfernandez/juniversalchardet | Character set detection (Tika) |
| `org.codelibs:jhighlight` | CDDL-1.0 **or** LGPL-2.1-or-later | CDDL-1.0 - source at https://github.com/codelibs/jhighlight | Source code highlighting (Tika) |

### Left out on purpose

`com.github.junrar:junrar`, which Tika's archive parser brings, is excluded in `Backend/build.gradle`: its UnRAR
licence is not an open-source license (the code may not be used to re-create the RAR compression algorithm), and RAR
is not among the file types Alpdesk AI reads.

## Frontend (Angular, shipped in the browser bundle)

| Component | License |
|---|---|
| `@angular/*` (core, common, compiler, forms, router, platform-browser, animations) | MIT |
| `@angular/cdk`, `@angular/material` | MIT |
| `@ngrx/store`, `@ngrx/effects`, `@ngrx/store-devtools` | MIT |
| `@jsverse/transloco`, `@jsverse/transloco-locale` (the frontend's languages, with `@jsverse/utils`) | MIT |
| `marked` | MIT; its license includes John Gruber's original Markdown notice (BSD-3-Clause style) |
| `highlight.js` (the chat's code blocks, only the languages of `code-highlight.util.ts`) | BSD-3-Clause |
| `rxjs` | Apache-2.0 |
| `@material/material-color-utilities` | Apache-2.0 |
| `tslib` | 0BSD |

### Fonts and icons

They end up in the bundle as files, not only as code:

| Component | License | Note |
|---|---|---|
| `@fontsource-variable/inter` (the Inter font) | SIL Open Font License 1.1 | The OFL allows redistribution and embedding; the font may not be sold on its own and keeps its name. |
| `material-icons` (Google's Material Icons font, filled and outlined) | Apache-2.0 | Icon font |

`ng build` writes the full license texts of everything in the bundle to `Frontend/dist/alpdesk-ai/3rdpartylicenses.txt`.

## Container image

The image of a release (`ghcr.io/xprojects-de/alpdesk-ai:<version>`) is based on `eclipse-temurin:25-jre-resolute`:
Ubuntu 26.04 with the Eclipse Temurin Java runtime (GPL-2.0 with Classpath exception), plus Ubuntu's **ffmpeg** package and
its libraries (GPL-2.0-or-later in Ubuntu's build). The application runs ffmpeg as a separate program to convert
uploaded audio and video; it is not linked into the jar, so its license does not extend to the project's code. The
sources of every Ubuntu package are available from Ubuntu (`apt-get source <package>`, https://launchpad.net/ubuntu),
those of Temurin from https://adoptium.net. The release's compose file also starts PostgreSQL with pgvector
(`pgvector/pgvector:pg18`, PostgreSQL License) and Caddy (Apache-2.0); their images are pulled from their publishers,
not published with the release.

## Sandbox image

The sandbox for the chat's Python and shell (`ghcr.io/xprojects-de/alpdesk-ai-sandbox:<version>`, built from
`Backend/podman/sandbox/Dockerfile`) is based on `ubuntu:26.04` and adds Ubuntu's packages `python3` (PSF-2.0), `python3-seccomp` and `libseccomp2`
(LGPL-2.1), `bash`, `findutils`, `diffutils`, `grep`, `gawk`, `sed`, `gzip` (GPL-3.0-or-later), `coreutils`, `jq`
(MIT), `sqlite3` (public domain), `file` (BSD-2-Clause), `bzip2` (bzip2 license), `xz-utils` (0BSD/public domain) and
`unzip` (Info-ZIP). They run as separate programs, unmodified, and do not extend to the project's code; their sources
are available from Ubuntu as above. The Python libraries in its venv, exactly as pinned in `Backend/podman/sandbox/requirements.txt`:

| Library | License |
|---|---|
| numpy (its wheel bundles OpenBLAS, BSD-3-Clause, and the GCC runtime, GPL-3.0 with the GCC Runtime Library Exception) | BSD-3-Clause |
| pandas, contourpy, cycler, kiwisolver | BSD-3-Clause |
| matplotlib (bundles FreeType, FTL, and Qhull) | Matplotlib License (PSF-based) |
| openpyxl, et_xmlfile, fonttools, pyparsing, six | MIT |
| pillow | MIT-CMU (HPND) |
| python-dateutil | Apache-2.0 or BSD-3-Clause |
| packaging | Apache-2.0 or BSD-2-Clause |

The sandbox's HTTP server runs from a venv of its own, exactly as pinned in `Backend/podman/sandbox/requirements-server.txt`:

| Library | License |
|---|---|
| starlette, uvicorn, click, idna | BSD-3-Clause |
| anyio, h11 | MIT |
| typing_extensions | PSF-2.0 |

The image carries `LICENSE`, `NOTICE` and this file under `/usr/share/doc/alpdesk-ai-sandbox/`.

## Embeddings image

`ghcr.io/xprojects-de/alpdesk-ai-embeddings` (built from `Backend/podman/ollama/Dockerfile`, optional) is Ollama's
official image `ollama/ollama` (MIT; Ubuntu-based, with the GPU runtime libraries Ollama ships under their vendors'
terms) with the embedding model **bge-m3** of the Beijing Academy of Artificial Intelligence (MIT) pulled into it,
unmodified. The sources of Ollama are at https://github.com/ollama/ollama, the model's card and license at
https://huggingface.co/BAAI/bge-m3, those of the Ubuntu packages from Ubuntu as above.

## Tools (not shipped)

Only for development, build and tests, in no artifact that goes to users: Gradle, the Micronaut and Shadow Gradle
plugins, micronaut-openapi (Apache-2.0), Spock and Groovy (Apache-2.0), Testcontainers, docker-java, JNA, Byte Buddy,
Objenesis, JUnit, Hamcrest; Angular CLI, TypeScript (Apache-2.0), ESLint, Prettier, Vitest, jsdom, angular-eslint,
typescript-eslint (MIT).

## In the jar and the release

Every jar carries under `META-INF/third-party/` the project's `LICENSE`, `NOTICE` and this file, the backend's license
report with the license files of every library (`backend/`) and the frontend's licenses (`frontend-licenses.txt`,
written by `ng build`). The public repository of the releases
(https://github.com/xprojects-de/alpdesk-ai) carries `LICENSE`, `NOTICE` and this file next to the compose files.

## Updating this list

The build checks every library of the jar: `./gradlew checkLicense` (part of `check` and of every jar build) fails when
a library has none of the licenses in `Backend/config/allowed-licenses.json` - permissive licenses for every library,
weak copyleft only for the libraries named there. A dependency update that brings a new license therefore stops the
build until someone has looked at it: decide, then add it to that file and to this one.

This file is the readable overview and is not generated: after a dependency update, compare it with
`./gradlew generateLicenseReport` (`Backend/build/reports/dependency-license/dependency-licenses.txt`); the frontend's
part comes from the `license` field of `node_modules/<package>/package.json` (`npm ls --omit=dev --all`). The
sandbox's Python libraries: `Backend/podman/sandbox/requirements.txt` and `requirements-server.txt` hold every one; their licenses are in each package's metadata
(`pip show <package>` in the image's venv).
