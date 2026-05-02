# Specs Catalog

Specs are CUJ-nested in this repository.

Canonical location:

`cuj/<cuj-slug>/specs/<spec-slug>/`

This top-level `specs/` folder is kept only as a compatibility placeholder.

Required files per spec:

1. `spec.md` from `templates/spec-template.md`
2. `tracking.md` from `templates/tracking-template.md`
3. `target/` git submodule to the implementation repository

Hard gates:

1. Specs can only be created after `cuj/<cuj-slug>/cuj.md` has `approval_status: approved`.
2. Implementation work for specs is blocked until CUJ approval is verified.

Recommended submodule command:

```bash
git submodule add -b <branch> <repo-url> cuj/<cuj-slug>/specs/<spec-slug>/target
```

Or use the bootstrap helper from repo root:

```bash
scripts/new-spec.sh \
  --cuj <cuj-slug> \
  --spec <spec-slug> \
  --spec-id <SPEC-NNN> \
  --title "<Feature Spec Title>" \
  --owner "<team-or-person>" \
  --repo <git-url> \
  --branch <branch-name>
```
