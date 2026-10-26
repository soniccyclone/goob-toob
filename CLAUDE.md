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
Every push to main re-renders `plans/*.org` and publishes them to GitHub
Pages at https://soniccyclone.github.io/goob-toob/ (the `plans` job in
`.github/workflows/ci.yml`) — no extra step needed to make a new plan
viewable.

## Conventions
- 100% Clojure/CLJS; dash.js is the only npm dep (via shadow-cljs).
- Anything not installed on the host runs in podman (`make help` lists targets).
- CLJS: never use `cljs.core/format` (DCE'd out of release builds).
- DB access: HoneySQL data structures, not raw SQL strings (see
  `plans/` for the migration plan). No `[:raw ...]` in query code either —
  if the DSL lacks a form, register one with `sql/register-fn!` in `db.clj`
  (see `[:now]`) and use that.

## Sync design: hard rules (Nathan's, not up for revision in a fix)
These are design decisions, not bugs. Do not "fix" them, do not propose
fixes that bend them, and do not plan around them without asking first.
- **The sync policy never seeks a viewer to close drift.** Drift is closed
  by playback rate only (`sync_core.cljc` `policy`: catch-up 1.05,
  nudge-slow 0.95, room slow-down 0.97) or not at all. No re-anchoring
  seeks, no "seek instead of a long nudge", no seek on return to 1.0.
  The one automatic seek on the books is the existing `:seek-back` for a
  viewer that ran *ahead* past `:seek-ahead`; it never seeks forward, and
  nothing new gets added beside it.
- **Lag is allowed.** A viewer who falls noticeably behind has two remedies,
  both deliberate: they reload the page (the lag hint already says so), or
  the host issues a seek, which moves everyone and is the room's one
  "force sync" control. Automation does neither on their behalf.
- **Pitch preservation stays on.** `preservesPitch` is never turned off,
  not even briefly during a nudge. Any audible pitch change is a
  regression.
- **ffmpeg stays a static build** on Linux (see the closed #33); a
  glibc-linked ffmpeg is not an acceptable swap, whatever the download
  flake of the day is.
