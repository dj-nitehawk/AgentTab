---
type: Reference
title: Project Overview
description: JetBrains plugin that opens a pinned Agent terminal tab running Claude Code or Codex and wires attention notifications plus path hotkeys.
tags: [overview, layout, architecture]
resource: README.md
---

# Project Overview

## Purpose

**AgentTab** (`dev.agenttab`) is an IntelliJ Platform plugin that opens a pinned editor tab named **Agent**, starts the configured agent CLI (Claude Code or Codex) in the project base path, and integrates IDE notifications and path-sending actions.

## Scope

- Single Gradle module; Kotlin sources under `src/main/kotlin/dev/agenttab/`.
- Builds against **Rider** via IntelliJ Platform Gradle (`rider(...)`); platform version and since-build from `gradle.properties`.
- Requires bundled **Terminal** plugin and `claude` or `codex` (on `PATH` or a configured command); `sh` + `curl` for notifications.
- MIT licensed. Version from `pluginVersion` in `gradle.properties`.

## Capabilities

| Area | Behavior |
| --- | --- |
| Startup | `OpenAgentOnStartupActivity` opens/pins an `AgentSessionFile` tab without stealing focus |
| Session | `AgentSessionEditor` hosts a local terminal; runs the agent command from settings plus notify flags as the terminal process |
| Settings | App-level **Settings > Tools > AgentTab**: agent type (Claude Code / Codex) + optional command |
| Attention | Launch-flag hook (Claude `--settings` Stop hook, Codex `-c notify`) POSTs on turn completion; IDE balloon + system notification |
| Path links | Terminal output paths like `src/file.ext:10` are clickable (must resolve under project base) |
| Hotkeys | `Alt+A` new pinned Agent tab (fresh session; titles `Agent`, `Agent 2`, ... lowest free via `newAgentSessionFile`; send actions target the last selected Agent tab, tracked by `AgentTabSelectionTracker` project listener); `Alt+P` path; `Alt+L` path+line/selection formats (see README) |

## Layout

| Path | Role |
| --- | --- |
| `src/main/kotlin/dev/agenttab/` | All plugin Kotlin sources (6 files) |
| `src/main/resources/META-INF/plugin.xml` | Plugin id, deps, extensions, actions |
| `src/main/resources/icons/` | Tab/action icons |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Build, platform, version |
| `.github/workflows/` | CI build/verify and release |

## Architecture (folded)

- **Style:** IntelliJ plugin; project-level service + editor provider + startup activity + actions.
- **Components:**
  - `AgentSessionFile` / `AgentSessionEditorProvider` / `AgentSessionEditor`: virtual file + terminal-backed editor (`AgentTabSession.kt`)
  - `AgentLauncher` / `AgentTerminalRunner`: shell terminal; cwd = project base path; process is `$SHELL -i -c '<effective command> notifyArgs(agentType)'` with `AGENT_TAB_NOTIFY_*` in env; tab closes on clean (exit 0) agent exit
  - `AgentType` / `AgentTabSettings` / `AgentTabConfigurable`: app-level persisted settings (`agenttab.xml`) and settings page (`AgentTabSettings.kt`)
  - `AgentAttentionNotificationBridge`: loopback HTTP `/notify` with `x-agenttab-token`; 1s debounce; suppress when Agent tab selected and IDE active
  - `AgentTerminalFileLinkHandler`: hover/click file refs in terminal buffer
  - `OpenNewAgentTabAction` + `SendToAgentAction` subclasses (`AgentTabActions.kt`); both open tabs via `openAgentTab` (focused + pinned)
- **Boundaries:** no separate modules; session disposable owns terminal/handlers. The notify server is shared by all Agent tabs and stops only on project close (service dispose).
- **External:** Terminal plugin APIs; shells out to `claude` / `codex`; HTTP only to `127.0.0.1`. Never writes agent config files (`~/.claude`, `~/.codex`).

## Status

Productive plugin with Gradle packaging and GitHub release workflow. No automated tests in-repo.

## Non-goals

- Not a multi-product or multi-plugin workspace (single artifact).
- Does not vendor or ship agent binaries.
- Grok CLI support was dropped: it has no launch-time hook injection, so it would need files in `~/.grok/hooks/`.

## Sources
- `README.md`
- `build.gradle.kts`
- `gradle.properties`
- `src/main/resources/META-INF/plugin.xml`
- `src/main/kotlin/dev/agenttab/`
