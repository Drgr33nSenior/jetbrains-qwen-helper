package family.thespry.qwen

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.*
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.MouseEvent
import java.nio.file.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import javax.swing.*

class QwenWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "QwenWorkstation"
    override fun getDisplayName() = "Qwen Workstation"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = QwenWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) { widget.dispose() }
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
class QwenWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    @Volatile private var text = "Qwen: checking"
    @Volatile private var details = "Checking…"
    @Volatile private var stopped = false
    private var statusBar: StatusBar? = null
    private var task: ScheduledFuture<*>? = null
    override fun ID() = "QwenWorkstation"
    override fun getPresentation() = this
    override fun getText() = text
    override fun getAlignment() = 0f
    override fun getTooltipText() = "Qwen Workstation status and actions"
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar; schedule(0) }
    private fun schedule(delay: Long) {
        if (!stopped) task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            refresh()
            schedule(QwenSettings.get().pollSeconds.coerceAtLeast(5).toLong())
        }, delay, TimeUnit.SECONDS)
    }
    private fun refresh() {
        if (stopped || project.isDisposed) return
        val settings = QwenSettings.get()
        val trust = runCatching { project.basePath?.let { Trust.trusted(settings.trust().read(), Path.of(it), Trust::real) } == true }.getOrDefault(false)
        val gateway = runCatching { Gateway.poll(AgentConfig.parse(SafeFiles.readString(Path.of(settings.config))).getValue("AGENT_BASE_URL")) }.getOrNull()
        text = gateway?.widget(trust) ?: "Qwen: unreachable"
        val bundle = runCatching { Bundles.health(Path.of(settings.currentBundle).toRealPath()).let { "${it.id}: ${if (it.ok) "integrity OK" else "SHA-256 mismatch"} (${it.date})" } }.getOrDefault("Bundle unavailable or invalid")
        val agent = runCatching { settings.acp().status() }.getOrDefault("ACP file invalid or unreadable")
        details = "${gateway?.let { "Phase: ${it.phase}\nModel: ${it.model}\nImages: ${it.images}\nActive requests: ${it.active}\nMCP reachable: ${it.mcp}" } ?: "Gateway unreachable"}\nQwen trusts this project: $trust\n$bundle\nAgent: $agent\n\nAir: only Git worktrees with verified backlinks can be discovered.\nSession working directories cannot be queried through a public Air API."
        Ui.later { if (!stopped && !project.isDisposed) statusBar?.updateWidget(ID()) }
    }
    override fun getClickConsumer() = Consumer<MouseEvent> { event ->
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(JTextArea(details).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; columns = 65; rows = 13 })
        fun button(label: String, action: () -> Unit) { panel.add(JButton(label).apply { addActionListener { action() } }) }
        button("Refresh") { Ui.background(project) { refresh() } }
        button("Trust for Qwen") { Ui.background(project) { project.basePath?.let { TrustActions.trust(project, Path.of(it)) } } }
        button("Revoke trust") { Ui.background(project) { project.basePath?.let { TrustActions.revoke(project, Path.of(it).toRealPath()) } } }
        button("Find project worktrees") { TrustActions.check(project) }
        button("Fix agent entry") { Ui.background(project) { QwenSettings.get().acp().fix(); Ui.changed(project); refresh() } }
        button("Regenerate bundle") { BundleAction.run(project) }
        button("Settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, QwenConfigurable::class.java) }
        JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel).setTitle("Qwen Workstation").setRequestFocus(true).createPopup().showUnderneathOf(event.component)
    }
    override fun dispose() { stopped = true; task?.cancel(true); statusBar = null }
}
