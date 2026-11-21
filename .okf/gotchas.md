---
type: Reference
title: Gotchas
description: Non-obvious traps for AgentTab plugin development and runtime.
tags: [gotcha, maintain]
---

# Gotchas

- Platform dependency is **Rider** (`rider(...)` in `build.gradle.kts`), not a generic multi-product matrix; plugin verifier uses `RD` + `platformVersion`. README wording is broader (“JetBrains IDE”). Prefer the Gradle target when changing platform setup.
- `since-build` is `263` (2026.3 line, `platformVersion = 2026.3`); `untilBuild` is open (`null`). Bumping platform requires coordinated `platformVersion` / `pluginSinceBuild` and verifier IDEs.
- Opening the Agent tab uses `FileEditorManagerEx.requestOpenFile(file, FileEditorOpenRequest.defaults().with...())` (experimental API; class moved from `fileEditor.ex` to `fileEditor` in 263, constructor became private, `openFile(file, request)` became `suspend`). The future may complete off-EDT, so follow-up pin/paste work goes through `invokeLater` with a disposed check. Re-check this on each platform bump.
- Runtime needs the selected agent (`claude` / `codex`, or the configured command) and Terminal plugin; missing either fails at use time, not at compile time. Notify needs `sh` + `curl`.
- Attention bridge binds **loopback only**, random port, token in `x-agenttab-token`; hook fails soft if IDE is gone.
- Notify `curl` must keep `--noproxy "*"`: an `http_proxy`/`HTTPS_PROXY` in the IDE env would otherwise route the `127.0.0.1` POST through the proxy and drop notifications.
- Notify hooks are passed as launch flags, never written to disk: Claude `--settings '<json>'` (Stop hook, merged with the user's own Stop hooks) and Codex `-c 'notify=["sh","-c",...]'`. Port/token stay in process env, not in the flags.
- Codex `notify` is a single program, so the `-c` override replaces a user `notify` from `~/.codex/config.toml` for that session. Codex also emits non-turn events (e.g. `approval-requested`); the inline script only POSTs on `agent-turn-complete`.
- The agent type picks the notify flags; the command must actually start that agent. Wrapper scripts that drop `"$@"` silently lose the flags (no notifications).
- Rider 2026.3's non-modal welcome screen is a real project (`~/RiderProjects/RiderHome`), so the startup activity opens an Agent tab there. The exact check (`WelcomeScreenProjectProvider.isWelcomeScreenProject`) is `@ApiStatus.Internal` and fails `verifyPlugin`; users disable it via advanced setting `welcome.screen.non.modal.enabled` instead.
- Notifications suppressed only when **Agent tab selected and IDE window active**; inactive window still notifies.
- Multiple Agent tabs share one notify server/token per project. Never tie its lifetime to a session disposable: closing the first tab used to stop it and silently break notifications in the others.
- Notification debounce: 1s (`NOTIFICATION_DEBOUNCE_MILLIS`).
- `AgentTerminalFileLinkHandler` uses reflection on `TerminalPanel.panelToCharCoords`. Platform terminal API drift can silently disable links if the method disappears.
- File links must resolve under the terminal working directory (project base); paths outside that root are ignored.
- Renamed grokLaunch (`dev.groklaunch`) -> AgentWindow (`dev.agentwindow`) -> AgentTab (`dev.agenttab`): each id change makes Rider treat it as a new plugin, so old installs must be removed manually and saved settings (`agentwindow.xml`) are not migrated to `agenttab.xml`. Old grokLaunch installs left `~/.grok/hooks/groklaunch-notify.json` + `bin/notify-ide.sh`; AgentTab does not manage them.
- The agent is the terminal process, not typed into a shell: `$SHELL -i -c '<effective command> <notify flags>'` via `ShellStartupOptions.shellCommand`, with `AGENT_TAB_NOTIFY_URL` / `AGENT_TAB_NOTIFY_TOKEN` passed as `envVariables`. Typing it with `sendCommandToExecute` echoed it twice (tty typeahead + prompt redraw) and leaked the token into shell history. `-i` keeps rc-file PATH/aliases; the effective command is parsed by `$SHELL` (`/bin/sh` if unset). When the process exits with code 0, `AgentSessionView` closes the Agent tab (`TerminalWidget.addTerminationCallback` + `FileEditorManager.closeFile`); non-zero or unknown exit (code read via `ShellTerminalWidget.getProcessTtyConnector(...).process`) keeps the tab open so the error stays visible. No fallback shell. Settings changes apply to newly opened tabs only.
- Platform 2026.3 is built for Java 25: JVM target must be 25 or inlining platform functions (e.g. `BaseState.enum`) fails to compile.
- Send-to-Agent pastes via clipboard + `ttyConnector.write`; opens/pins an `AgentSessionEditor` if needed.

## When to update OKF

Sync `.okf/` when changing architecture/boundaries, `plugin.xml` contracts/actions/hotkeys, platform/deps/version commands, CI/release, notify/hook protocol, or conventions. If unaffected, say so before finishing. Full day-to-day gates: `AGENTS.md`.

## Sources
- `build.gradle.kts`
- `src/main/kotlin/dev/agenttab/AgentAttentionNotificationBridge.kt`
- `src/main/kotlin/dev/agenttab/AgentTabSettings.kt`
- `src/main/kotlin/dev/agenttab/AgentTabSession.kt`
- `src/main/kotlin/dev/agenttab/AgentTerminalFileLinkHandler.kt`
- `.github/workflows/build.yml`
