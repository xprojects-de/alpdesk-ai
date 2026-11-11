#!/bin/sh
# Checks the installation's files in Backend/podman/public - run from the repository root, by the pull-request checks
# and by the release workflow before anything is built:
# - docker-compose.proxy.yml (behind an own reverse proxy) is docker-compose.prod.yml without Caddy: the app's settings
#   must stay identical in both, so a setting is described once (README.md, .env.example) and an installation can
#   switch between the files;
# - every image of ours in them carries the application's version (app.version): a checkout of the tag v<version>
#   must start exactly that release, so the tags are raised in the same change as the version.
set -eu

public=Backend/podman/public
version="$(sed -n 's/^app\.version=//p' Backend/src/main/resources/application.properties)"
[ -n "$version" ] || { echo "no app.version in Backend/src/main/resources/application.properties"; exit 1; }

# The environment block of the service alpdesk-ai: from "  alpdesk-ai:" to the next service or top-level key, within
# that from "    environment:" to "    volumes:".
app_environment() {
  awk '/^  alpdesk-ai:$/ { inside = 1; next }
       inside && /^(  [a-z]|[a-z])/ { inside = 0 }
       inside && /^    environment:$/ { block = 1 }
       inside && /^    volumes:$/ { block = 0 }
       inside && block' "$1"
}

prod="$(app_environment "$public/docker-compose.prod.yml")"
proxy="$(app_environment "$public/docker-compose.proxy.yml")"
[ -n "$prod" ] || { echo "no environment block of alpdesk-ai found in docker-compose.prod.yml"; exit 1; }
if [ "$prod" != "$proxy" ]; then
  echo "the app's environment differs between docker-compose.prod.yml and docker-compose.proxy.yml:"
  printf '%s\n' "$prod" > /tmp/compose-prod-environment
  printf '%s\n' "$proxy" > /tmp/compose-proxy-environment
  diff /tmp/compose-prod-environment /tmp/compose-proxy-environment || true
  exit 1
fi
echo "docker-compose.proxy.yml carries the app's settings of docker-compose.prod.yml"

# Each file with the line its image must have - an exact line, so a file that lost or renamed it fails too.
failed=0
expect() {
  if ! grep -qxF "$2" "$public/$1"; then
    echo "$1 lacks the line: $2"
    failed=1
  fi
}
expect docker-compose.prod.yml "    image: ghcr.io/xprojects-de/alpdesk-ai:${version}"
expect docker-compose.proxy.yml "    image: ghcr.io/xprojects-de/alpdesk-ai:${version}"
expect docker-compose.embeddings.yml "    image: ghcr.io/xprojects-de/alpdesk-ai-embeddings:${version}"
expect docker-compose.tenant-sandbox.yml "    image: \${SANDBOX_IMAGE:-ghcr.io/xprojects-de/alpdesk-ai-sandbox}:${version}"
expect sandbox-example/Dockerfile "ARG BASE=ghcr.io/xprojects-de/alpdesk-ai-sandbox:${version}"
if [ "$failed" -ne 0 ]; then
  echo "the images' tags must be app.version ${version} - raise them together"
  exit 1
fi
echo "the images in ${public} carry version ${version}"
