# Agent instructions

## OKF knowledge set

This repository uses `.okf/` as compact operational memory for AI agents.

OKF v0.1: non-reserved `.md` files need YAML frontmatter with non-empty `type`, `title`, and `description`; `index.md` is a directory listing; only bundle-root `index.md` may have frontmatter (`okf_version`).

Normative OKF use/update gates live in this file. `.okf/index.md` and the gotchas update reminder are secondary. Setup and full re-audits follow a separate procedure (the okf skill, where available).

### Before work

Match OKF depth to blast radius:

- Local/small change: `.okf/index.md` + conventions/gotchas (and workflows if commands/build surface is involved).
- Cross-cutting, plugin.xml contracts/actions, notify/hook protocol, platform/deps, or new surface: core set (overview, conventions, workflows, gotchas).

OKF guides. It does not replace checking source, tests, or manifests for exact behavior.

### During work

Preserve conventions, boundaries, and workflows from OKF. On conflict with source/tests/generated artifacts/manifests: prefer verified current behavior, update OKF, mention the correction.

### OKF content discipline

- OKF is operational memory, not a change log or second documentation site.
- Add facts only when they guide future decisions: boundaries, contracts, commands, non-obvious failure modes, or owner-approved policies. Omit routine implementation details and exhaustive API/test inventories.
- Keep each detailed fact in one topic owner. Elsewhere, use a short summary or link only when needed for navigation.
- Update or replace existing guidance rather than appending another account of the same behavior. Remove superseded material in the same edit.
- Before finishing an OKF edit, check for duplication, stale detail, and unnecessary reading cost. Preserve critical safety, compatibility, persistence, and owner-decision constraints.

### Before finishing

Evaluate OKF when the change hits the update triggers in [`.okf/gotchas.md`](.okf/gotchas.md#when-to-update-okf) (Small shape has no `maintenance.md`).

Update OKF only when existing guidance becomes incorrect or incomplete, or the change introduces durable operational knowledge future agents would otherwise rediscover. A trigger requires evaluation, not an automatic addition.

Task is incomplete until OKF is synced or reported unaffected: `OKF unaffected (existing guidance remains accurate and sufficient)`, or `OKF unaffected (non-behavioral edit)` for pure comment/typo/formatting changes.

### General

Subject to project conventions in OKF/`conventions.md` and this file:

- Focused, minimal changes; prefer existing patterns.
- Do not use em or en dashes as prose punctuation. Use commas, parentheses, or separate sentences instead. Hyphens remain valid in compound words, bullets, code, commands, CLI flags, identifiers, paths, versions, ranges, quotations, and exact terms.
- Do not hand-edit generated artifacts (Gradle/`build/` outputs, `.intellijPlatform/` caches); regenerate via project commands instead. If a path is not listed but is clearly generated output, leave it alone and regenerate.
- Agent notify hooks are generated in `AgentTabSession.kt` (`notifyArgs`) and passed as launch flags; never write to agent config dirs (`~/.claude`, `~/.codex`, `~/.gemini`).
- If behavior changes, run the smallest relevant command (`./gradlew buildPlugin`, `verifyPlugin`, or `runIde`). If not run, state the blocker.
