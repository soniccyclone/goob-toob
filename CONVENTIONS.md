# Implementation Conventions

These conventions are **binding on every `goob-toob-implementation-*` repository**. Each implementation repo's `CLAUDE.md` MUST reference this document as load-bearing.

This file does **not** apply to `goob-toob` itself (the design repo) — the design repo ships markdown only, no binaries.

## 1. Containerization

- Every runtime component (API, transcoder, lobby tick service, web SSR, worker, etc.) MUST run in a container.
- Dev, CI, and production all use the same container image. No "works on my machine."
- Multi-stage Dockerfiles. Build dependencies stay out of the runtime image.
- Final stage uses `distroless`, `alpine`, or equivalent slim base.
- Containers run as a non-root `USER`.
- Every long-lived service defines a `HEALTHCHECK`.
- Base images MUST be multi-arch (`linux/arm64` + `linux/amd64`). Single-arch base images are forbidden — they silently break native dev on at least one of the target dev platforms.

## 2. Make as the front door

- The repository's primary automation interface is `make`.
- `make` with no target MUST run `help`.
- Standard targets present in every implementation repo:
  - `setup` — install or pull dev dependencies (containers, tools).
  - `build` — build all images locally for the host arch.
  - `dev` / `up` — start the dev stack.
  - `down` — tear the dev stack down.
  - `logs` — tail dev stack logs.
  - `test` — run tests inside containers.
  - `clean` — remove generated artifacts and stopped containers.
  - `help` — list all targets with one-line descriptions.
- OS detection in the Makefile where macOS-vs-Linux behavior differs.

## 3. Multi-arch dev — native both ways, no emulation

- One `Dockerfile`, one `docker-compose.yml`, one `devcontainer.json`. No platform flags, no architecture branching anywhere in dev tooling.
- Docker's default native-build behavior IS the strategy: M-series Macs build `arm64`, x86 hosts build `amd64`. No QEMU, ever, in dev — emulated `ffmpeg` is unacceptable for both performance and battery life.
- Base images being multi-arch (§ 1) is what makes "do nothing arch-specific in dev" actually work.

## 4. Multi-arch publish via buildx

- CI publishes a multi-arch manifest (`linux/arm64,linux/amd64`) for every released image.
- Build via `docker buildx build --platform linux/arm64,linux/amd64 --push`.
- End users pulling the image get the right arch automatically — that is a property of the registry manifest, not of the user's pull command.

## 5. Deployment via GitHub Container Registry (GHCR)

- Every service publishes its image to GHCR under the implementation repo's namespace: `ghcr.io/<owner>/goob-toob-implementation-<stack>/<service>`.
- **One image per service.** No mega-image with `$SERVICE`-switched entrypoints.
- Tagging policy on every published image:
  - `:latest` and `:main-<short-sha>` on every green-CI merge to `main`.
  - `:vX.Y.Z` on every semver git tag.
  - Every tag carries a multi-arch manifest (§ 4).
- Workflow triggers: push to `main` AND tag push.
- Authentication uses the workflow's `GITHUB_TOKEN` with `packages: write` permission. No personal access tokens.
- Each GHCR package MUST be set to **public** visibility. The default is private, which breaks anonymous pulls and makes "easy install" a lie.
- Build attestations: SBOM (`--sbom=true`) and provenance (`--provenance=true`) emitted on publish. Self-hosters get a real supply-chain audit trail at zero ongoing cost.
- Anonymous pull MUST work: `docker pull ghcr.io/<owner>/.../api:latest` with no prior `docker login`.
- The only allowed substitution is self-hosted GitHub Enterprise without Packages enabled, in which case an equivalent OCI registry may be used. No other deviation.

## 6. The "60-second install" deliverable

Publishing images is half the deal. Each implementation repo MUST also ship at the repo root:

- `compose.yml` — pulls the published GHCR images by tag, parameterized via `.env`.
- `.env.example` — safe defaults, ready to copy.
- A README "Install in 60 seconds" section: three commands or fewer, ending with a running stack on a fresh host that has only Docker installed.

Without those three artifacts, "easy as `docker pull`" is a slogan, not a feature.

## 7. CLAUDE.md reference

Every implementation repo's `CLAUDE.md` MUST contain a top-level section that references this document as binding, e.g.:

> Implementation conventions are defined in [`CONVENTIONS.md`](https://github.com/<owner>/goob-toob/blob/main/CONVENTIONS.md) of the design repo. Treat that file as load-bearing — its rules override anything in this repo that contradicts them.

This keeps conventions DRY across implementations and lets a regenerated implementation in a new stack inherit them automatically.
