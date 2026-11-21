package dev.agenttab

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

enum class AgentType(val displayName: String, val defaultCommand: String) {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
}

@Service(Service.Level.APP)
@State(name = "AgentTabSettings", storages = [Storage("agenttab.xml")])
class AgentTabSettings : SimplePersistentStateComponent<AgentTabSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var agentType by enum(AgentType.CLAUDE)
        var command by string()
    }

    /** User command, or the agent's default binary when blank. */
    val effectiveCommand: String
        get() = state.command?.takeIf { it.isNotBlank() } ?: state.agentType.defaultCommand

    companion object {
        fun getInstance(): AgentTabSettings = service()
    }
}

class AgentTabConfigurable : BoundConfigurable("AgentTab") {

    private val settings = AgentTabSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row("Agent:") {
            comboBox(AgentType.entries, textListCellRenderer { it?.displayName })
                .bindItem(settings::agentType.toNullableProperty())
        }
        row("Command:") {
            textField()
                .columns(COLUMNS_LARGE)
                .bindText({ settings.command.orEmpty() }, { settings.command = it.trim().ifEmpty { null } })
                .comment(
                    "Leave empty to run <code>claude</code> or <code>codex</code> from PATH. " +
                        "May include a full path and extra flags. Must match the selected agent, and any wrapper " +
                        "script must pass its arguments through (<code>\"$@\"</code>). Applies to newly opened Agent tabs."
                )
        }
    }
}
