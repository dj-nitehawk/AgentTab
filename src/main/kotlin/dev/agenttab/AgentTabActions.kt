package dev.agenttab

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile

private val LAST_AGENT_FILE = Key.create<AgentSessionFile>("agenttab.lastAgentFile")

/** Remembers the most recently selected Agent tab so the send actions target it. */
class AgentTabSelectionTracker(private val project: Project) : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        (event.newFile as? AgentSessionFile)?.let { project.putUserData(LAST_AGENT_FILE, it) }
    }
}

/** Opens a new pinned Agent tab running a fresh agent session. */
class OpenNewAgentTabAction : AnAction(), DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) openAgentTab(project, newAgentSessionFile(project))
        }
    }
}

abstract class SendToAgentAction : AnAction(), DumbAware {

    final override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = sourceContext(event) != null
    }

    final override fun actionPerformed(event: AnActionEvent) {
        val context = sourceContext(event) ?: return
        sendToAgentPrompt(context.project, textFor(context))
    }

    protected abstract fun textFor(context: SourceContext): String

    protected data class SourceContext(
        val project: Project,
        val editor: com.intellij.openapi.editor.Editor,
        val file: VirtualFile,
    )

    private fun sourceContext(event: AnActionEvent): SourceContext? {
        val project = event.project ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (file.isDirectory || file is AgentSessionFile) return null
        return SourceContext(project, editor, file)
    }
}

class SendCurrentFilePathToAgentAction : SendToAgentAction() {

    override fun textFor(context: SourceContext): String = context.file.displayPath(context.project)
}

class SendCurrentFileLocationToAgentAction : SendToAgentAction() {

    override fun textFor(context: SourceContext): String {
        val path = context.file.displayPath(context.project)
        val selection = context.editor.selectionModel
        val start = selection.selectionStartPosition
        val end = selection.selectionEndPosition

        if (selection.hasSelection() && start != null && end != null && start.line != end.line) {
            return "$path:${start.line + 1}-${end.line + 1}"
        }

        val selectedText = selection.selectedText
        if (selectedText != null && selectedText.isSingleSymbol()) {
            val line = if (start != null) start.line + 1 else context.editor.caretModel.logicalPosition.line + 1
            return "$selectedText ($path:$line)"
        }

        return "$path:${context.editor.caretModel.logicalPosition.line + 1}"
    }
}

private fun VirtualFile.displayPath(project: Project): String {
    val basePath = project.basePath ?: return path
    return FileUtil.getRelativePath(basePath, path, '/') ?: path
}

private fun String.isSingleSymbol(): Boolean = isNotBlank() && none { it.isWhitespace() }

private fun sendToAgentPrompt(project: Project, text: String) {
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val openAgentFiles = FileEditorManagerEx.getInstanceEx(project).openFiles.filterIsInstance<AgentSessionFile>()
        val file = project.getUserData(LAST_AGENT_FILE)?.takeIf { it in openAgentFiles }
            ?: openAgentFiles.firstOrNull()
            ?: newAgentSessionFile(project)
        openAgentTab(project, file) { it.pasteTextToPrompt(text) }
    }
}

/** Opens (or selects) [file] as a focused, pinned Agent tab. Must be called on the EDT. */
private fun openAgentTab(project: Project, file: AgentSessionFile, onOpened: (AgentSessionEditor) -> Unit = {}) {
    val fileEditorManager = FileEditorManagerEx.getInstanceEx(project)
    file.putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true)
    val request = FileEditorOpenRequest.defaults()
        .withSelectAsCurrent(true)
        .withReuseOpen(true)
        .withRequestFocus(true)
        .withPin(true)
    fileEditorManager.requestOpenFile(file, request).thenAccept { composite ->
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            fileEditorManager.windows
                .firstOrNull { it.isFileOpen(file) }
                ?.setFilePinned(file, true)

            composite.allEditors
                .filterIsInstance<AgentSessionEditor>()
                .firstOrNull()
                ?.let(onOpened)
        }
    }
}
