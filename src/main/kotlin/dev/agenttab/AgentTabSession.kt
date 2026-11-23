package dev.agenttab

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.testFramework.LightVirtualFile

private const val AGENT_TAB_NAME = "Agent"

private fun buildAgentCommand(): String {
    val settings = AgentTabSettings.getInstance()
    return "${settings.effectiveCommand} ${notifyArgs(settings.state.agentType)}"
}

// POSTs to the attention bridge; never fails the agent.
private const val NOTIFY_CURL =
    "curl -sS -X POST --noproxy \"*\" -H \"x-agenttab-token: \$AGENT_TAB_NOTIFY_TOKEN\" --max-time 3 " +
        "\"\$AGENT_TAB_NOTIFY_URL\" >/dev/null 2>&1 || true"

// POSTs agy's stdin state JSON to the attention bridge; never fails the agent.
private const val AGY_STATE_CURL =
    "curl -sS -X POST --noproxy \"*\" -H \"x-agenttab-token: \$AGENT_TAB_NOTIFY_TOKEN\" " +
        "-H \"x-agenttab-session: \$AGENT_TAB_SESSION_ID\" --data-binary @- --max-time 3 " +
        "\"\$AGENT_TAB_NOTIFY_URL\" >/dev/null 2>&1 || true"

/** Launch flags that wire the agent's attention signal to the bridge, without touching its config files. */
private fun notifyArgs(agentType: AgentType): String = when (agentType) {
    // Stop runs when the main agent finishes responding (not on user interrupt).
    AgentType.CLAUDE -> {
        val hook = """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":${doubleQuote(NOTIFY_CURL)}}]}]}}"""
        "--settings ${shellQuote(hook)}"
    }
    // notify gets the event JSON as its last argument; only turn completion should alert.
    AgentType.CODEX -> {
        val script = "case \"\$1\" in *agent-turn-complete*) $NOTIFY_CURL;; esac"
        val notify = listOf("sh", "-c", script, "agenttab").joinToString(",", "notify=[", "]") { doubleQuote(it) }
        "-c ${shellQuote(notify)}"
    }
    // agy ignores hooks in settings.json; its session-only title command receives state JSON on stdin on every change.
    AgentType.AGY -> {
        val settings = """{"title":{"type":"command","enabled":true,"command":${doubleQuote(AGY_STATE_CURL)}}}"""
        "--settings ${shellQuote(settings)}"
    }
}

/** JSON / TOML basic string literal. */
private fun doubleQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

/** New Agent session titled "Agent", "Agent 2", ... using the lowest title not already open. */
fun newAgentSessionFile(project: Project): AgentSessionFile {
    val openTitles = FileEditorManager.getInstance(project).openFiles.filterIsInstance<AgentSessionFile>().map { it.name }.toSet()
    val title = generateSequence(1) { it + 1 }
        .map { if (it == 1) AGENT_TAB_NAME else "$AGENT_TAB_NAME $it" }
        .first { it !in openTitles }
    return AgentSessionFile(project.basePath, title)
}

class AgentSessionFile(
    val workingDirectory: String?,
    title: String = AGENT_TAB_NAME,
) : LightVirtualFile(title, AgentSessionFileType, "") {

    init {
        isWritable = false
    }

    override fun getFileType(): FileType = AgentSessionFileType
}

object AgentSessionFileType : FileType {
    override fun getName(): String = "AgentSession"

    override fun getDescription(): String = "AgentTab session"

    override fun getDefaultExtension(): String = ""

    override fun getIcon(): Icon = IconLoader.getIcon("/icons/agenttab.svg", AgentSessionFileType::class.java)

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true
}

class AgentSessionEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is AgentSessionFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        AgentSessionEditor(project, file as AgentSessionFile)

    override fun getEditorTypeId(): String = "agenttab.session"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class AgentSessionEditor(
    project: Project,
    private val file: AgentSessionFile,
) : UserDataHolderBase(), FileEditor {

    private val sessionDisposable: Disposable = Disposer.newDisposable("agentTab-session")
    private val view = AgentSessionView(project, file, sessionDisposable)

    override fun getComponent(): JComponent = view.component

    override fun getPreferredFocusedComponent(): JComponent? = view.preferredFocusComponent()

    fun pasteTextToPrompt(text: String) {
        view.pasteTextToPrompt(text)
    }

    override fun getName(): String = AGENT_TAB_NAME

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        Disposer.dispose(sessionDisposable)
    }
}

private class AgentSessionView(
    private val project: Project,
    file: AgentSessionFile,
    parent: Disposable,
) {
    private val widget: TerminalWidget = AgentLauncher.launch(project, parent, file.workingDirectory)

    init {
        // Close the tab on a clean agent exit; keep it open on failure (or unknown status) so the error stays visible.
        widget.addTerminationCallback({
            val process = widget.ttyConnector?.let { ShellTerminalWidget.getProcessTtyConnector(it) }?.process
            val exitCode = process?.takeIf { it.waitFor(2, TimeUnit.SECONDS) }?.exitValue()
            if (exitCode != 0) return@addTerminationCallback
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) FileEditorManager.getInstance(project).closeFile(file)
            }
        }, parent)
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(widget.component, BorderLayout.CENTER)
    }

    fun preferredFocusComponent(): JComponent? = widget.preferredFocusableComponent

    fun pasteTextToPrompt(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))

        val focusComponent = preferredFocusComponent() ?: widget.component
        IdeFocusManager.getInstance(project).requestFocus(focusComponent, true)

        widget.ttyConnector?.write(text)
    }
}

private object AgentLauncher {

    fun launch(project: Project, parent: Disposable, workingDirectory: String?): TerminalWidget {
        val bridge = AgentAttentionNotificationBridge.getInstance(project)
        val endpoint = bridge.start()
        val sessionId = UUID.randomUUID().toString()
        Disposer.register(parent) { bridge.clearSessionState(sessionId) }
        // Run the agent as the terminal process (not typed into a shell), so the command is never echoed or saved
        // to shell history. -i loads the user's rc files (PATH, aliases). The tab closes when the agent exits.
        val shell = System.getenv("SHELL") ?: "/bin/sh"
        val options = ShellStartupOptions.Builder()
            .workingDirectory(workingDirectory)
            .shellCommand(listOf(shell, "-i", "-c", buildAgentCommand()))
            .envVariables(
                mapOf(
                    "AGENT_TAB_NOTIFY_URL" to endpoint.url,
                    "AGENT_TAB_NOTIFY_TOKEN" to endpoint.token,
                    "AGENT_TAB_SESSION_ID" to sessionId,
                ),
            )
            .build()
        val widget = AgentTerminalRunner(project).startShellTerminalWidget(parent, options, true)
        JBTerminalWidget.asJediTermWidget(widget)?.let { AgentTerminalFileLinkHandler.install(project, workingDirectory, it, parent) }

        return widget
    }
}

private class AgentTerminalRunner(project: Project) : LocalTerminalDirectRunner(project)
