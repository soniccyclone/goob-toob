# CUJ Catalog

Store CUJs for this product as folders:

`cuj/<cuj-slug>/cuj.md`

Each CUJ folder should also include:

- `pr-stack.md` for stacked PR review/merge/deploy order
- `specs/<spec-slug>/` for one or more specs linked to the CUJ

Approval requirement:

- Set `approval_status: approved` in `cuj/<cuj-slug>/cuj.md` before creating specs.
- Do not begin implementation until CUJ approval is present.

Use `templates/cuj-template.md` for every new journey.
Use `templates/pr-stack-template.md` for CUJ-level PR stack tracking.
