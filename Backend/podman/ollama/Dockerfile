# Ollama with an embedding model baked in, for the company's knowledge without a cloud provider (README.md "Embeddings
# with a local Ollama"). The model is pulled while the image is built, so the running container needs no internet -
# build it where the internet is reachable (behind a proxy: podman passes HTTP(S)_PROXY to the build), then move it
# with `podman save` / `podman load` if the target has none.
#
#   podman build -t localhost/alpdesk/ollama-embeddings:latest ollama
#   podman build --build-arg EMBEDDING_MODEL=nomic-embed-text -t localhost/alpdesk/ollama-embeddings:latest ollama
ARG OLLAMA_VERSION=latest
FROM docker.io/ollama/ollama:${OLLAMA_VERSION}

# bge-m3: multilingual (German included), 1024 dimensions, up to 8192 tokens per passage - the catalog entry's
# dimension must match the model's.
ARG EMBEDDING_MODEL=bge-m3
# Runs as any user, on a read-only root (Kubernetes' "restricted" pod security): the model lies where every user may
# read it, nothing prunes it at the start, and HOME is /tmp - Ollama writes a key of its own there at every start
# (only for ollama.com, which is never reached), so /tmp must be writable (a tmpfs, an emptyDir).
ENV EMBEDDING_MODEL=${EMBEDDING_MODEL} \
    OLLAMA_HOST=0.0.0.0:11434 \
    OLLAMA_MODELS=/usr/share/ollama/models \
    OLLAMA_NOPRUNE=1 \
    HOME=/tmp

# A server only for the pull: started, waited for, stopped again - the model stays in the image.
RUN ollama serve > /tmp/ollama-build.log 2>&1 & server=$!; \
    for _ in $(seq 1 60); do ollama list > /dev/null 2>&1 && break; sleep 1; done; \
    ollama pull "$EMBEDDING_MODEL" || { cat /tmp/ollama-build.log; exit 1; }; \
    kill "$server"; wait "$server" 2> /dev/null; rm -rf /tmp/ollama-build.log /tmp/.ollama; \
    chmod -R a+rX /usr/share/ollama

# Not root: a fixed, numeric user - Kubernetes' runAsNonRoot can only check a numeric one.
USER 10001:10001

# Ready once the server answers and has the model.
HEALTHCHECK --interval=10s --timeout=5s --retries=12 CMD ollama show "$EMBEDDING_MODEL" > /dev/null || exit 1

EXPOSE 11434
# The base image's entrypoint and command: ollama serve.
