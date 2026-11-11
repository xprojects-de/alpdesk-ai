#!/bin/sh
# Switches the test stack between WAM (Authelia in front, people from the IDM) and plain (password login, users and
# groups kept by hand) - README.md. Each mode keeps its own database and files; switching stops the stack in the old
# mode and starts it in the new one - the images as they are (after a code change: podman compose up -d --build).
#   ./mode.sh            the mode in use
#   ./mode.sh wam|plain  switch (then, the first time in a mode: python3 setup.py base, full, models)
set -eu
cd "$(dirname "$0")"
current=$(sed -n 's/^STACK_MODE=//p' .env 2>/dev/null || true)
if [ $# -eq 0 ]; then
  echo "${current:-wam}"
  exit 0
fi
case "$1" in
  wam) settings='STACK_MODE=wam
APP_WAM_ENABLED=true
COMPOSE_PROFILES=wam
CADDYFILE=Caddyfile
VOLUME_SUFFIX=' ;;
  plain) settings='STACK_MODE=plain
APP_WAM_ENABLED=false
COMPOSE_PROFILES=
CADDYFILE=Caddyfile.plain
VOLUME_SUFFIX=-plain' ;;
  *) echo "usage: $0 [wam|plain]" >&2; exit 2 ;;
esac
# Down with every profile - Authelia too, whatever mode ran before.
podman compose --profile wam down >/dev/null 2>&1 || true
printf '# Written by mode.sh - the mode of the stack (README.md)\n%s\n' "$settings" > .env
podman compose up -d
echo "the stack runs in mode $1"
