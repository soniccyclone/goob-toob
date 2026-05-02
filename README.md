# goob-toob

Self-hosted video streaming with watch-together lobbies.

This repository is the **design home** for goob-toob. It holds:

- Critical User Journeys under [`cuj/`](cuj/)
- OpenSpec specifications nested under each CUJ
- Per-CUJ stacked PR manifests
- Templates for new CUJs, specs, and stacks under [`templates/`](templates/)

Implementation code lives in sibling repositories named `goob-toob-implementation-<stack>`. Each spec mounts its target implementation repo as a git submodule under `cuj/<cuj-slug>/specs/<spec-slug>/target/`.

Cross-cutting implementation conventions are not in this repo. They live in [`soniccyclone/goob-toob-conventions`](https://github.com/soniccyclone/goob-toob-conventions) and are consumed by every implementation repo.

## Active implementations

_(none yet — scaffolding underway)_

## Workflow

Driven by the [`cuj-design`](https://github.com/exokomodo/skill-cuj-design) skill. Short version:

1. Author a CUJ in `cuj/<slug>/cuj.md` (start from [`templates/cuj-template.md`](templates/cuj-template.md)).
2. Approve it (`approval_status: approved`).
3. Create specs under `cuj/<slug>/specs/<spec-slug>/`.
4. Submodule the target implementation repo under each spec's `target/`.
5. Track stacked PR order in `cuj/<slug>/pr-stack.md`.

Bootstrap helper for steps 3-5:

```bash
scripts/new-spec.sh \
  --cuj <cuj-slug> \
  --spec <spec-slug> \
  --spec-id SPEC-NNN \
  --title "<Feature Spec Title>" \
  --owner "<team-or-person>" \
  --repo <git-url> \
  --branch <branch-name>
```
