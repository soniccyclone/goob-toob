#!/bin/sh
# Build the plans site into gen/www: an index listing every plans/*.org plus the
# rendered plan pages, styled with the org-plan skill's bigblow theme (vendored
# into site/themes). Fails loudly rather than publishing a partial site.
#
# The plan .org files are the source of truth; plans/*.html in the repo is just
# the local snapshot from plan day. srht.site's CSP only allows styles/scripts
# from 'self', so site/vendor.py mirrors the theme's CDN assets in afterwards.
set -eu
set -o pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

rm -rf gen
mkdir -p gen/org

# Each plan: the skill's SETUPFILE and sequence-diagram INCLUDE paths are
# machine-local, so point them at the vendored copies in site/, then append
# nav links.
for org in plans/*.org; do
  [ "$(basename "$org")" = README.org ] && continue
  slug=$(basename "$org" .org)
  {
    sed -e "s|^#+SETUPFILE: .*|#+SETUPFILE: $ROOT/site/themes/default.setup|" \
        -e "s|\"[^\"]*diagrams/sequence\.html\"|\"$ROOT/site/diagrams/sequence.html\"|" "$org"
    printf '\n* Links\n- [[file:index.org][All plans]]\n- [[https://git.sr.ht/~nathanb/goob-toob/tree/master/item/plans][Plans directory]]\n'
  } > "gen/org/$slug.org"
done

# Index page, newest plan first. Date comes from the filename prefix, the
# title from #+TITLE, the status from the "# Status:" stamp line.
{
  printf '#+TITLE: goob-toob plans\n'
  printf '#+OPTIONS: toc:nil num:nil author:nil date:nil ^:nil\n'
  printf '#+SETUPFILE: %s/site/themes/default.setup\n\n' "$ROOT"
  printf 'Rendered plans for [[https://git.sr.ht/~nathanb/goob-toob][goob-toob]], newest first.\n\n'
  for org in $(ls plans/*.org | grep -v README | sort -r); do
    slug=$(basename "$org" .org)
    date=$(echo "$slug" | grep -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2}')
    title=$(sed -n 's/^#+TITLE: //p' "$org" | head -1)
    status=$(sed -n 's/^# Status: //p' "$org" | head -1)
    printf -- '- %s — [[file:%s.org][%s]]\n' "$date" "$slug" "$title"
    [ -n "$status" ] && printf '  %s\n' "$status"
  done
  printf '\nSource: [[file:../plans/][plans/]] in the repo; each plan ships with the rendered\nhtml snapshot from its plan day, but this site re-renders from the .org\non every build.\n'
} > gen/org/index.org

init=""; [ -f "$HOME/.emacs" ] && init="-l $HOME/.emacs"   # never -Q: the real init may have htmlize
emacs --batch $init -l site/build.el 2>&1 | tee gen/build.log
if grep -qiE '^(Error|Debugger entered|Symbol.s function definition is void)' gen/build.log; then
  echo "build: emacs reported an error above" >&2; exit 1
fi
test -f gen/www/index.html || { echo "build: no index.html produced" >&2; exit 1; }

grep -ho 'https://[^"]*' site/themes/*.setup | sort -u > gen/urls.txt
python3 site/vendor.py gen/www gen/urls.txt
echo "build: $(find gen/www -name '*.html' | wc -l | tr -d ' ') pages in gen/www"
