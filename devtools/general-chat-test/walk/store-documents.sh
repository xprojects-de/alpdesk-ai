#!/bin/sh
# Puts the invented sample documents of devtools/task-documents into the stack's file store for the walk's second task
# (walk.md, B21): ablage/belege/eingang/2026 to read, ablage/belege/export/2026 to write - emptied first, so the task
# reads every document again.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
container=general-chat-test-sftp
store=/home/check/ablage/belege
podman exec --user check "$container" sh -c "rm -rf $store && mkdir -p $store/eingang/2026 $store/export/2026"
for file in "$here"/../../task-documents/eingang/2026/*; do
  podman cp "$file" "$container:$store/eingang/2026/"
done
podman exec "$container" chown -R check "$store"
podman exec --user check "$container" ls "$store/eingang/2026"
