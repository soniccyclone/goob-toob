#!/bin/sh
# Print the next release tag for HEAD, or "skip".
# The bump comes from the "Release:" git trailer on commits since the last v* tag:
#   none                        patch
#   Release: minor              minor
#   Release: major              major (wins over minor)
#   Release: skip on HEAD, or HEAD already tagged   skip
# Only the trailer block counts; prose that mentions a marker does nothing.
set -eu
last=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || echo v0.0.0)
if [ "$last" = v0.0.0 ]; then range=HEAD; else range="$last..HEAD"; fi
trailer() { git log "$@" --format='%(trailers:key=Release,valueonly)' | tr '[:upper:]' '[:lower:]' | tr -d ' '; }
if trailer -1 | grep -qx skip; then echo skip; exit 0; fi
if [ -z "$(git rev-list "$range")" ]; then echo skip; exit 0; fi
bumps=$(trailer "$range")
v=${last#v}
major=${v%%.*}; rest=${v#*.}; minor=${rest%%.*}; patch=${rest#*.}
if echo "$bumps" | grep -qx major; then echo "v$((major + 1)).0.0"
elif echo "$bumps" | grep -qx minor; then echo "v$major.$((minor + 1)).0"
else echo "v$major.$minor.$((patch + 1))"; fi
