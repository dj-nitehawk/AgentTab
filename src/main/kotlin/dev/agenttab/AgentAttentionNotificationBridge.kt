package dev.agenttab

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Service(Service.Level.PROJECT)
class AgentAttentionNotificationBridge(private val project: Project) : Disposable {

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var token: String? = null
    private var lastNotificationAt = 0L
    private val activeNotifications = mutableListOf<Notification>()
    private val agySessionStates = ConcurrentHashMap<String, AgyState>()

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (event.newFile is AgentSessionFile) {
                        expireAttentionNotifications()
                    }
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    if (ideFrame.project == project && isAgentSessionSelected()) {
                        expireAttentionNotifications()
                    }
                }
            },
        )
    }

    /** Starts the server once per project; it stays up until the project closes (service dispose), shared by all Agent tabs. */
    fun start(): Endpoint {
        val existingServer = server
        val existingToken = token
        if (existingServer != null && existingToken != null) {
            return Endpoint(
                url = "http://127.0.0.1:${existingServer.address.port}/notify",
                token = existingToken,
            )
        }

        val newToken = createToken()
        val bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
        val newServer = HttpServer.create(bindAddress, 0)
        val newExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agentTab-attention-bridge").apply {
                isDaemon = true
            }
        }

        newServer.createContext("/notify") { exchange ->
            handleNotify(exchange, newToken)
        }
        newServer.executor = newExecutor
        newServer.start()

        server = newServer
        executor = newExecutor
        token = newToken

        return Endpoint(
            url = "http://127.0.0.1:${newServer.address.port}/notify",
            token = newToken,
        )
    }

    fun clearSessionState(sessionId: String) {
        agySessionStates.remove(sessionId)
    }

    private fun handleNotify(exchange: HttpExchange, expectedToken: String) {
        try {
            if (exchange.requestMethod != "POST") {
                exchange.sendStatus(405)
                return
            }

            val actualToken = exchange.requestHeaders.getFirst("x-agenttab-token")
            if (actualToken != expectedToken) {
                exchange.sendStatus(401)
                return
            }

            val body = exchange.requestBody.use { it.readBytes() }
            exchange.sendStatus(204)

            val sessionId = exchange.requestHeaders.getFirst("x-agenttab-session")?.takeIf { it.isNotBlank() }
            if (sessionId == null) {
                showAttentionNotification()
            } else {
                when (evaluateAgyTransition(sessionId, body)) {
                    AgyNotification.TURN_COMPLETE -> showAttentionNotification(waitingForApproval = false)
                    AgyNotification.APPROVAL_NEEDED -> showAttentionNotification(waitingForApproval = true)
                    AgyNotification.NONE -> Unit
                }
            }
        } catch (_: Exception) {
            if (exchange.responseCode == -1) {
                exchange.sendStatus(500)
            }
        } finally {
            exchange.close()
        }
    }

    private fun evaluateAgyTransition(sessionId: String, body: ByteArray): AgyNotification {
        val current = parseAgyState(body) ?: return AgyNotification.NONE
        val previous = agySessionStates.put(sessionId, current)
            ?: return if (current.isAwaitingApproval) AgyNotification.APPROVAL_NEEDED else AgyNotification.NONE

        return when {
            !previous.isAwaitingApproval && current.isAwaitingApproval -> AgyNotification.APPROVAL_NEEDED
            previous.isActive && current.agentState in AGY_FINISHED_STATES -> AgyNotification.TURN_COMPLETE
            else -> AgyNotification.NONE
        }
    }

    private fun parseAgyState(body: ByteArray): AgyState? {
        if (body.isEmpty()) return null
        return try {
            val json = JsonParser.parseString(String(body, StandardCharsets.UTF_8)).asJsonObject
            val agentState = json["agent_state"]?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val toolConfirmationPending =
                json["tool_confirmation_pending"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            AgyState(agentState = agentState, toolConfirmationPending = toolConfirmationPending)
        } catch (_: Exception) {
            null
        }
    }

    private fun showAttentionNotification(waitingForApproval: Boolean = false) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || (isAgentSessionSelected() && isProjectWindowActive())) return@invokeLater

            val now = System.currentTimeMillis()
            if (now - lastNotificationAt < NOTIFICATION_DEBOUNCE_MILLIS) return@invokeLater
            lastNotificationAt = now

            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(ATTENTION_NOTIFICATION_TITLE, NotificationType.INFORMATION)

            val systemMessage = if (waitingForApproval) {
                "Agent is waiting for approval in ${project.name}."
            } else {
                "Agent finished work in ${project.name}."
            }
            activeNotifications += notification
            notification.notify(project)
            SystemNotifications.getInstance().notify(
                NOTIFICATION_GROUP_ID,
                ATTENTION_NOTIFICATION_TITLE,
                systemMessage,
            )
        }
    }

    private fun expireAttentionNotifications() {
        val notifications = activeNotifications.toList()
        activeNotifications.clear()
        notifications.forEach { it.expire() }
    }

    private fun isAgentSessionSelected(): Boolean {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val selectedFiles = fileEditorManager.selectedFiles
        if (selectedFiles.any { it is AgentSessionFile }) return true

        return selectedFiles.isEmpty() && fileEditorManager.openFiles.singleOrNull() is AgentSessionFile
    }

    private fun isProjectWindowActive(): Boolean = WindowManager.getInstance().getFrame(project)?.isActive == true

    private fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        token = null
        agySessionStates.clear()
    }

    override fun dispose() {
        expireAttentionNotifications()
        stop()
    }

    data class Endpoint(
        val url: String,
        val token: String,
    )

    private data class AgyState(
        val agentState: String,
        val toolConfirmationPending: Boolean,
    ) {
        val isActive: Boolean
            get() = agentState in AGY_ACTIVE_STATES

        val isAwaitingApproval: Boolean
            get() = toolConfirmationPending || agentState == "reviewing"
    }

    private enum class AgyNotification {
        NONE,
        TURN_COMPLETE,
        APPROVAL_NEEDED,
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "AgentTab"
        private const val ATTENTION_NOTIFICATION_TITLE = "Agent needs attention"
        private const val NOTIFICATION_DEBOUNCE_MILLIS = 1_000L
        private val AGY_ACTIVE_STATES = setOf("working", "tool_use", "thinking")
        private val AGY_FINISHED_STATES = setOf("idle", "error")

        fun getInstance(project: Project): AgentAttentionNotificationBridge = project.service()

        private fun createToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

private fun HttpExchange.sendStatus(statusCode: Int) {
    sendResponseHeaders(statusCode, -1)
}
