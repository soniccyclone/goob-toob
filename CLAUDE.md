# goob-toob — agent notes

## Planning mode
All planning-mode work in this repo goes through the **org-plan skill**
(`~/.config/opencode/skills/org-plan`), with one local override: plans are
saved **in this repo**, not in `~/.local/share/opencode/plans/`.

- Path: `plans/YYYY-MM-DD-<slug>.org` — the date prefix is mandatory; newest
  date wins when figuring out what's current.
- Render the html next to it with
  `~/.config/opencode/skills/org-plan/render.sh plans/<file>.org` and commit
  both files together.
- Add a row to the index table in `plans/README.org` (written / plan / status).
- When a plan ships, stamp the org file with
  `# Status: approved <date>; shipped as <tag/version> on <date>.` and update
  the index row.
- Plans are read-only until approved; don't implement until told to.

## Plans site
Pushing master republishes every `plans/*.org` to
https://nathanb.srht.site/goob-toob-plans/ via `.build.yml` — no extra step
needed to make a new plan viewable.

## Conventions
- 100% Clojure/CLJS; dash.js is the only npm dep (via shadow-cljs).
- Anything not installed on the host runs in podman (`make help` lists targets).
- CLJS: never use `cljs.core/format` (DCE'd out of release builds).
- DB access: HoneySQL data structures, not raw SQL strings (see
  `plans/` for the migration plan).
