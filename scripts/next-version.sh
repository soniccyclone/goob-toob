#!/bin/sh
# Print the next release tag for HEAD, or "skip".
#   default                 patch bump of the last v* tag
#   [minor] in any commit since that tag   minor bump
#   [major] in any commit since that tag   major bump
#   [skip release] in HEAD's message, or HEAD already tagged   skip
set -eu
last=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || echo v0.0.0)
if [ "$last" = v0.0.0 ]; then range=HEAD; else range="$last..HEAD"; fi
if git log -1 --format=%B | grep -qi '\[skip release\]'; then echo skip; exit 0; fi
if [ -z "$(git rev-list "$range")" ]; then echo skip; exit 0; fi
log=$(git log "$range" --format=%B)
v=${last#v}
major=${v%%.*}; rest=${v#*.}; minor=${rest%%.*}; patch=${rest#*.}
if echo "$log" | grep -qi '\[major\]'; then echo "v$((major + 1)).0.0"
elif echo "$log" | grep -qi '\[minor\]'; then echo "v$major.$((minor + 1)).0"
else echo "v$major.$minor.$((patch + 1))"; fi
