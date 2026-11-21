# AgentTab

JetBrains IDE plugin that opens a pinned **Agent** editor tab for the current project and starts a coding agent CLI in it: **Claude Code** (`claude`) or **Codex** (`codex`).

The plugin targets IntelliJ Platform `2026.3` (`since-build 263`) and uses the bundled JetBrains Terminal plugin.

## Requirements

- JetBrains IDE based on IntelliJ Platform `2026.3` or newer
- Bundled **Terminal** plugin enabled
- `claude` or `codex` available on `PATH` (or a full path set in the plugin settings)
- `sh` and `curl` for attention notifications (Linux/macOS)
- JDK 25 when building from source

## Settings

**Settings > Tools > AgentTab** (application-wide):

| Setting | Description |
| --- | --- |
| Agent | `Claude Code` (default) or `Codex`. Selects how attention notifications are wired. |
| Command | Command to run. Empty means `claude` / `codex` from `PATH`. May be a full path and include extra flags, e.g. `claude --model opus`. Must start the selected agent. Wrapper scripts must forward their arguments (`"$@"`). |

Changes apply to newly opened **Agent** tabs.

Rider 2026.3's new welcome screen is itself a project (`~/RiderProjects/RiderHome`), so an **Agent** tab opens there too when you close a solution. To avoid it, turn off the non-modal welcome screen in **Settings > Advanced Settings** (`welcome.screen.non.modal.enabled`).

## What it does

- Opens a pinned editor tab named **Agent** when a project opens.
- Starts the configured agent in the tab, using the project base path as the working directory.
- Lets you hover and click project-relative terminal output paths like `src/file.ext` and `src/file.ext:10` to easily navigate to that file.
- Shows `Agent needs attention` IDE and system notifications when the agent finishes a turn.
- Suppresses those notifications while the **Agent** tab is selected and the IDE window is active.
- Still shows notifications if the IDE is not the active window, even when the **Agent** tab is selected.
- Closes active Agent attention IDE notifications when the **Agent** tab is selected again or IDE is reactivated with **Agent** selected.
- Sends file/paths to the plugin's **Agent** tab with hotkeys.

## Attention notifications

The plugin wires each agent's turn-complete signal through launch flags. It never writes to the agents' config files (`~/.claude`, `~/.codex`).

Flow:

1. On launch, the plugin starts a loopback HTTP bridge and sets `AGENT_TAB_NOTIFY_URL` / `AGENT_TAB_NOTIFY_TOKEN` for the agent process.
2. It appends a notify hook to the command:
   - **Claude Code:** `--settings '{"hooks":{"Stop":[...]}}'`. The `Stop` hook runs when Claude finishes responding. It is added alongside any `Stop` hooks you already have.
   - **Codex:** `-c 'notify=["sh","-c",...]'`. Codex passes the event JSON as an argument; only `agent-turn-complete` events notify. This overrides any `notify` program in `~/.codex/config.toml` for that session.
3. The hook runs an inline `curl` POST with header `x-agenttab-token` and always succeeds (fail-open, so it never blocks the agent).

Notifications:

- Appear after the agent finishes responding, including prompts that do not modify files.
- Do not appear while the **Agent** tab is currently selected and the IDE window is active.
- Still appear if the IDE is not the active window, even when the **Agent** tab is selected.
- Include a IDE bubble and a native system notification when supported by the OS and IDE notification settings.
- Active IDE bubbles are dismissed automatically when the **Agent** tab is selected again or the IDE is reactivated with **Agent** selected.

## Hotkeys

| Hotkey | Action | Sent text |
| --- | --- | --- |
| `Alt+A` | Open New Agent Tab | Nothing. Opens a new pinned Agent tab with a fresh agent session. Extra tabs are titled **Agent 2**, **Agent 3**, ... |
| `Alt+P` | Send Current File Path to Agent | Relative path from project root, or absolute path if no project base path is available. |
| `Alt+L` | Send Current File Location to Agent | Current file path plus line information. |

`Alt+P` and `Alt+L` send to the most recently selected Agent tab (or open one if none is open).

`Alt+L` formats text as follows:

- Caret with no selection: `path/to/file.ext:line`
- Multi-line selection: `path/to/file.ext:startLine-endLine`
- Single-symbol selection: `symbol (path/to/file.ext:line)`

Line numbers are 1-based.

## Building from source

```bash
./gradlew runIde        # launch a sandbox IDE with the plugin
./gradlew buildPlugin   # produce build/distributions/*.zip
./gradlew verifyPlugin  # run IntelliJ Plugin Verifier
```
