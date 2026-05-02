---
spec_id: SPEC-<NNN>
title: <Feature Spec Title>
source_cujs:
  - cuj/<cuj-slug>/cuj.md
cuj_approval_required: true
cuj_approval_status: approved
status: draft
owner: <team-or-person>
created: <YYYY-MM-DD>
updated: <YYYY-MM-DD>
target_repo: <git-url>
target_branch: <branch-name>
target_submodule_path: cuj/<cuj-slug>/specs/<spec-slug>/target
---

# Problem

State the user and business problem in one short paragraph.

# Goals

1. <goal>
2. <goal>

# Non-Goals

1. <non-goal>
2. <non-goal>

# User Journey Mapping

List how this spec covers the source CUJ(s).

1. `CUJ-<NNN>`: <mapping summary>

# Requirements

## Functional

1. <requirement>
2. <requirement>

## Non-Functional

1. <reliability/security/performance requirement>
2. <operational requirement>

# Acceptance Criteria

1. Given <context>, when <action>, then <observable result>.
2. Given <context>, when <action>, then <observable result>.

# UX and API Notes

Record key UI/API contracts and payload expectations.

# Rollout Plan

1. <step>
2. <step>

# Stacked PR Plan

- CUJ stack manifest: `cuj/<cuj-slug>/pr-stack.md`
- Planned PR branch: <branch-name>
- Stack position: <1-based-order>
- Depends on spec(s): <spec-slug>[, <spec-slug>]
- Unblocks spec(s): <spec-slug>[, <spec-slug>]

# Implementation Gate

Implementation must not start until the linked CUJ approval is `approved`.

# Risks and Mitigations

1. Risk: <risk>
   Mitigation: <mitigation>
2. Risk: <risk>
   Mitigation: <mitigation>

# Validation Plan

1. <test plan item>
2. <monitoring/metric verification>

# Implementation Tracking

The implementation repository is mounted as a submodule at:

`<target_submodule_path>`

Expected setup command:

```bash
git submodule add -b <target_branch> <target_repo> <target_submodule_path>
```
