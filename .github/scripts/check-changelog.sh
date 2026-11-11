#!/bin/sh
# Every release names what it changed: CHANGELOG.md must have a dated section for the version - "## [1.4.0] - 2026-10-15".
# The release workflow checks it first, before anything is built or pushed.
#
#   .github/scripts/check-changelog.sh <version>
set -eu

version="${1:?usage: $0 <version>}"
escaped=$(printf '%s' "$version" | sed 's/\./\\./g')
grep -q "^## \[${escaped}\] - [0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}$" CHANGELOG.md || {
  echo "CHANGELOG.md has no dated section \"## [${version}] - <yyyy-mm-dd>\" - write it (and replace \"unreleased\") before the release"
  exit 1
}
