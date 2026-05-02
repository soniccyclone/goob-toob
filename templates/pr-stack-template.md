# CUJ PR Stack

- CUJ: <cuj-slug>
- Last Updated: <YYYY-MM-DD>

Use this file to track stacked PR order for this CUJ. Keep each spec as a separate PR where possible.

## Stack Order

| Order | Spec Slug   | Branch        | PR            | Depends On | Review Status | Merge Status | Deploy Order | Notes   |
| ----- | ----------- | ------------- | ------------- | ---------- | ------------- | ------------ | ------------ | ------- |
| 1     | <spec-slug> | <branch-name> | <https://...> | none       | planned       | not-merged   | 1            | <notes> |

## Rules

1. Lower order PRs are reviewed and merged first.
2. A PR must not merge until all `Depends On` PRs are merged.
3. Deployment order can match merge order or intentionally differ; document why in `Notes`.
