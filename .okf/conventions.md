---
type: Reference
title: Conventions
description: Package layout, naming, and plugin patterns used in AgentTab.
tags: [conventions, layout]
---

# Conventions

## Naming and package

- Root package: `dev.agenttab` (matches `pluginGroup` / plugin id `dev.agenttab`).
- Kotlin files are feature-oriented top-level types (e.g. `AgentTabSession.kt` holds session editor stack; `AgentTabActions.kt` holds send-to-Agent actions).
- User-facing tab name is the constant `Agent` (`AGENT_TAB_NAME`).
- Notification group id: `AgentTab` (must match `plugin.xml` and bridge constants).

## Style

- Kotlin, JVM target 25; prefer small private helpers over deep hierarchies.
- IntelliJ APIs: prefer `DumbAware` for startup/actions so indexing does not block.
- UI work on EDT via `ApplicationManager.getApplication().invokeLater` with `project.isDisposed` checks.
- Disposables: attach terminal/handlers to session disposable; register cleanup with `Disposer`.

## Plugin registration

- Declare extensions, actions, and static project listeners in `src/main/resources/META-INF/plugin.xml` (`projectListeners`: `AgentTabSelectionTracker` on `FileEditorManagerListener`). Service-scoped listeners (`AgentAttentionNotificationBridge`) subscribe via `messageBus.connect(this)`.
- File editor provider id: `agenttab.session`.
- Action ids: `dev.agenttab.OpenNewAgentTab` (`Alt+A`), `dev.agenttab.SendCurrentFilePathToAgent`, `dev.agenttab.SendCurrentFileLocationToAgent`.
- Platform deps: `com.intellij.modules.platform` + `org.jetbrains.plugins.terminal`.

## Errors and validation

- Notify HTTP: method/token checks; inline hook command fails soft (`curl` errors ignored, `|| true`).
- Shell args quoted with `shellQuote` (single-quote + escape); JSON/TOML strings inside them with `doubleQuote`. The user's command is inserted raw so it can carry flags.

## Config and secrets

- Build/publish env names only (never commit values): `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`, `CHANGE_NOTES`.
- Runtime notify: `AGENT_TAB_NOTIFY_URL`, `AGENT_TAB_NOTIFY_TOKEN`, and `AGENT_TAB_SESSION_ID` passed as terminal process env (`ShellStartupOptions.envVariables`; hook reads them; multi-project and multi-tab safe because values are not baked into hook flags).
- Version/platform knobs live in `gradle.properties` (`pluginGroup`, `pluginName`, `pluginVersion`, `pluginSinceBuild`, `platformVersion`).

## Sources
- `src/main/kotlin/dev/agenttab/`
- `src/main/resources/META-INF/plugin.xml`
- `build.gradle.kts`
- `gradle.properties`
