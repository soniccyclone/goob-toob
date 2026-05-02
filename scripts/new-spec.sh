#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/new-spec.sh \
    --cuj <cuj-slug> \
    --spec <spec-slug> \
    --spec-id <SPEC-NNN> \
    --title "<Feature Spec Title>" \
    --owner "<team-or-person>" \
    --repo <git-url> \
    --branch <branch-name>

Example:
  scripts/new-spec.sh \
    --cuj booking-reschedule \
    --spec booking-reschedule \
    --spec-id SPEC-001 \
    --title "Reschedule Booking" \
    --owner "product-platform" \
    --repo git@github.com:autobutler-org/autobutler.git \
    --branch feature/reschedule-booking
EOF
}

CUJ_SLUG=""
SPEC_SLUG=""
SPEC_ID=""
SPEC_TITLE=""
OWNER=""
TARGET_REPO=""
TARGET_BRANCH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cuj)
      CUJ_SLUG="$2"
      shift 2
      ;;
    --spec)
      SPEC_SLUG="$2"
      shift 2
      ;;
    --spec-id)
      SPEC_ID="$2"
      shift 2
      ;;
    --title)
      SPEC_TITLE="$2"
      shift 2
      ;;
    --owner)
      OWNER="$2"
      shift 2
      ;;
    --repo)
      TARGET_REPO="$2"
      shift 2
      ;;
    --branch)
      TARGET_BRANCH="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$CUJ_SLUG" || -z "$SPEC_SLUG" || -z "$SPEC_ID" || -z "$SPEC_TITLE" || -z "$OWNER" || -z "$TARGET_REPO" || -z "$TARGET_BRANCH" ]]; then
  echo "Error: Missing one or more required arguments." >&2
  usage
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CUJ_DIR="$ROOT_DIR/cuj/$CUJ_SLUG"
CUJ_DOC="$CUJ_DIR/cuj.md"
SPEC_DIR="$CUJ_DIR/specs/$SPEC_SLUG"
TARGET_SUBMODULE_PATH="cuj/$CUJ_SLUG/specs/$SPEC_SLUG/target"
TARGET_SUBMODULE_ABS="$ROOT_DIR/$TARGET_SUBMODULE_PATH"
PR_STACK_TEMPLATE="$ROOT_DIR/templates/pr-stack-template.md"
PR_STACK_DOC="$CUJ_DIR/pr-stack.md"
SPEC_TEMPLATE="$ROOT_DIR/templates/spec-template.md"
TRACKING_TEMPLATE="$ROOT_DIR/templates/tracking-template.md"

if [[ ! -f "$PR_STACK_TEMPLATE" || ! -f "$SPEC_TEMPLATE" || ! -f "$TRACKING_TEMPLATE" ]]; then
  echo "Error: Missing template files under templates/." >&2
  exit 1
fi

if [[ ! -f "$CUJ_DOC" ]]; then
  echo "Error: Missing CUJ document: cuj/$CUJ_SLUG/cuj.md" >&2
  echo "Create and approve the CUJ first (set approval_status: approved), then create specs." >&2
  exit 1
fi

CUJ_APPROVAL_STATUS="$(awk -F': ' '/^approval_status:/ { print $2; exit }' "$CUJ_DOC" | tr -d '\r' | xargs)"
if [[ "$CUJ_APPROVAL_STATUS" != "approved" ]]; then
  echo "Error: CUJ is not approved (approval_status: ${CUJ_APPROVAL_STATUS:-missing})." >&2
  echo "Set approval_status: approved in cuj/$CUJ_SLUG/cuj.md before creating specs." >&2
  exit 1
fi

if [[ -d "$SPEC_DIR" ]]; then
  echo "Error: Spec directory already exists: $SPEC_DIR" >&2
  exit 1
fi

if [[ -e "$TARGET_SUBMODULE_ABS" ]]; then
  echo "Error: Target submodule path already exists: $TARGET_SUBMODULE_PATH" >&2
  exit 1
fi

mkdir -p "$CUJ_DIR"
if [[ ! -f "$PR_STACK_DOC" ]]; then
  cp "$PR_STACK_TEMPLATE" "$PR_STACK_DOC"
fi

mkdir -p "$SPEC_DIR"
cp "$SPEC_TEMPLATE" "$SPEC_DIR/spec.md"
cp "$TRACKING_TEMPLATE" "$SPEC_DIR/tracking.md"

TODAY="$(date +%F)"

sed -i '' "s|<NNN>|${SPEC_ID#SPEC-}|g" "$SPEC_DIR/spec.md"
sed -i '' "s|SPEC-<NNN>|$SPEC_ID|g" "$SPEC_DIR/spec.md"
sed -i '' "s|<Feature Spec Title>|$SPEC_TITLE|g" "$SPEC_DIR/spec.md"
sed -i '' "s|<cuj-slug>|$CUJ_SLUG|g" "$SPEC_DIR/spec.md"
sed -i '' "s|<spec-slug>|$SPEC_SLUG|g" "$SPEC_DIR/spec.md"
sed -i '' "s|<team-or-person>|$OWNER|g" "$SPEC_DIR/spec.md"
sed -i '' "s|<YYYY-MM-DD>|$TODAY|g" "$SPEC_DIR/spec.md"
sed -i '' "s|<git-url>|$TARGET_REPO|g" "$SPEC_DIR/spec.md"
sed -i '' "s|<branch-name>|$TARGET_BRANCH|g" "$SPEC_DIR/spec.md"

sed -i '' "s|SPEC-<NNN>|$SPEC_ID|g" "$SPEC_DIR/tracking.md"
sed -i '' "s|<YYYY-MM-DD>|$TODAY|g" "$SPEC_DIR/tracking.md"
sed -i '' "s|<cuj-slug>|$CUJ_SLUG|g" "$SPEC_DIR/tracking.md"
sed -i '' "s|<spec-slug>|$SPEC_SLUG|g" "$SPEC_DIR/tracking.md"
sed -i '' "s|<git-url>|$TARGET_REPO|g" "$SPEC_DIR/tracking.md"
sed -i '' "s|<branch-name>|$TARGET_BRANCH|g" "$SPEC_DIR/tracking.md"

if grep -q "<cuj-slug>" "$PR_STACK_DOC"; then
  sed -i '' "s|<cuj-slug>|$CUJ_SLUG|g" "$PR_STACK_DOC"
fi
if grep -q "<YYYY-MM-DD>" "$PR_STACK_DOC"; then
  sed -i '' "s|<YYYY-MM-DD>|$TODAY|g" "$PR_STACK_DOC"
fi

if grep -q "| <spec-slug> |" "$PR_STACK_DOC"; then
  awk -v spec="$SPEC_SLUG" -v branch="$TARGET_BRANCH" '
    {
      if (index($0, "| <spec-slug> | <branch-name> | <https://...> | none | planned | not-merged | 1 | <notes> |") > 0) {
        print "| 1 | " spec " | " branch " | <https://...> | none | planned | not-merged | 1 | created by bootstrap |"
      } else {
        print $0
      }
    }
  ' "$PR_STACK_DOC" > "$PR_STACK_DOC.tmp"
  mv "$PR_STACK_DOC.tmp" "$PR_STACK_DOC"
elif ! grep -q "| $SPEC_SLUG |" "$PR_STACK_DOC"; then
  NEXT_ORDER="$(awk -F'|' '
    BEGIN { max = 0 }
    /^[|][[:space:]]*[0-9]+[[:space:]]*[|]/ {
      gsub(/ /, "", $2)
      if ($2 + 0 > max) max = $2 + 0
    }
    END { print max + 1 }
  ' "$PR_STACK_DOC")"
  printf '| %s | %s | %s | <https://...> | none | planned | not-merged | %s | created by bootstrap |\n' "$NEXT_ORDER" "$SPEC_SLUG" "$TARGET_BRANCH" "$NEXT_ORDER" >> "$PR_STACK_DOC"
fi

git -C "$ROOT_DIR" submodule add -b "$TARGET_BRANCH" "$TARGET_REPO" "$TARGET_SUBMODULE_PATH"

cat <<EOF
Created:
  - cuj/$CUJ_SLUG/pr-stack.md
  - $TARGET_SUBMODULE_PATH
  - cuj/$CUJ_SLUG/specs/$SPEC_SLUG/spec.md
  - cuj/$CUJ_SLUG/specs/$SPEC_SLUG/tracking.md

Next steps:
  1) Review and refine spec content.
  2) Commit the new files and .gitmodules.
EOF
