---
type: Playbook
title: Workflows
description: Setup, build, run sandbox IDE, verify, and release commands for AgentTab.
tags: [build, ops]
resource: README.md
---

# Workflows

## Setup

- JDK **25** required (IntelliJ Platform 2026.3 is built for Java 25).
- Use Gradle wrapper (`./gradlew`); version pinned in `gradle/wrapper/gradle-wrapper.properties`.
- `claude`, `codex`, or `agy` must be on `PATH` (or set as the command in **Settings > Tools > AgentTab**); Terminal plugin must be enabled in the target IDE.
- Local secrets: use env vars for signing/publish; do not put values in repo (see `.gitignore` for `.env`, `local.properties`).

## Build and run

| Command | Purpose |
| --- | --- |
| `./gradlew runIde` | Sandbox IDE with the plugin |
| `./gradlew buildPlugin` | Plugin zip under `build/distributions/` |
| `./gradlew verifyPlugin` | IntelliJ Plugin Verifier (RD / `platformVersion`) |

Gradle properties of note: configuration cache and build cache enabled in `gradle.properties`; CI deliberately passes `--no-configuration-cache --no-parallel` to avoid intermittent platform dependency resolution failures.

No lint/format tasks are configured.

## Testing

No automated tests. Validate with `buildPlugin` / `verifyPlugin`, plus manual `runIde` checks for behavior changes (startup pin, notify, hotkeys, file links).

Manual attention check: open project, wait for turn end with Agent tab unfocused or IDE inactive, confirm balloon; with Agent tab selected and window active, confirm suppress.

## CI and release

- `.github/workflows/build.yml`: `workflow_dispatch`; JDK 25 Temurin; `buildPlugin` then `verifyPlugin`.
- `.github/workflows/release.yml`: `workflow_dispatch` with patch/minor/major; bumps `pluginVersion` in `gradle.properties`; runs `buildPlugin` (not `verifyPlugin`); commits and pushes `main` + tag `v*`, and creates a GitHub Release with the distribution zip.

## Dependencies (folded)

Authoritative plugin versions live in `build.gradle.kts` and `settings.gradle.kts`; version/platform keys live in `gradle.properties` (see [Conventions](conventions.md#config-and-secrets)).

| Item | Detail |
| --- | --- |
| Language/runtime | Kotlin JVM, Java 25 |
| Build | Gradle wrapper + IntelliJ Platform Gradle Plugin (`settings.gradle.kts`) + Kotlin JVM and Changelog plugins (`build.gradle.kts`) |
| Platform | `rider(platformVersion)` with `useInstaller = false`; bundled `org.jetbrains.plugins.terminal` |
| `kotlin.stdlib.default.dependency` | `false` in `gradle.properties` (platform-provided) |

## Sources
- `README.md`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `.github/workflows/build.yml`
- `.github/workflows/release.yml`
