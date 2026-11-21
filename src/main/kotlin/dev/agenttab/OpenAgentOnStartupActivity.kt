package dev.agenttab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class OpenAgentOnStartupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val fileEditorManager = FileEditorManagerEx.getInstanceEx(project)
            val file = fileEditorManager.openFiles
                .filterIsInstance<AgentSessionFile>()
                .firstOrNull()
                ?: newAgentSessionFile(project)

            file.putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true)
            val request = FileEditorOpenRequest.defaults()
                .withSelectAsCurrent(false)
                .withReuseOpen(true)
                .withRequestFocus(false)
                .withPin(true)
            fileEditorManager.requestOpenFile(file, request).thenAccept {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    fileEditorManager.windows
                        .firstOrNull { it.isFileOpen(file) }
                        ?.setFilePinned(file, true)
                }
            }
        }
    }
}
