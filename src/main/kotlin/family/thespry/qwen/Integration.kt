package family.thespry.qwen

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.actionSystem.*
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap

object Ui {
    fun later(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)
    fun background(project: Project?, block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try { block() } catch (e: Exception) { notify(project, if (e is UserError) e.message ?: "Operation refused" else "Operation failed (${e.javaClass.simpleName}). Review paths and file formats in Settings.", NotificationType.ERROR) }
        }
    }
    fun notify(project: Project?, text: String, type: NotificationType = NotificationType.INFORMATION) {
        later { if (project?.isDisposed != true) NotificationGroupManager.getInstance().getNotificationGroup("Qwen Workstation").createNotification(text, type).notify(project) }
    }
    fun confirm(project: Project?, text: String, yes: String): Boolean {
        var result = false
        ApplicationManager.getApplication().invokeAndWait {
            result = Messages.showDialog(project, text, "Qwen Workstation", arrayOf(yes, "Cancel"), 1, Messages.getWarningIcon()) == 0
        }
        return result
    }
    fun changed(project: Project?) = notify(project, "Change saved. Start a new AI Chat or Air session; existing sessions retain their previous configuration.")
}

object TrustActions {
    private val prompted = ConcurrentHashMap.newKeySet<String>()
    fun check(project: Project) = Ui.background(project) {
        val raw = project.basePath ?: return@background
        if (!TrustedProjects.isProjectTrusted(project) || project.isDisposed) return@background
        val root = Path.of(raw).toRealPath()
        val settings = QwenSettings.get()
        if (root.toString() in settings.neverProjects) return@background
        if (!Trust.trusted(settings.trust().read(), root, Trust::real)) {
            if (settings.autoTrust) { trust(project, root); return@background }
            offer(project, root)
        } else {
            Worktrees.discover(root).filter { !Trust.trusted(settings.trust().read(), it, Trust::real) }.forEach { offer(project, it) }
        }
    }
    private fun offer(project: Project, root: Path) {
        if (root.toString() in QwenSettings.get().neverProjects || !prompted.add(root.toString())) return
        Ui.later {
            if (project.isDisposed) return@later
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("Qwen Workstation")
                .createNotification("Qwen Code doesn't trust this project, so its workstation tools (web search, retrieval, memory) are off here.\n$root", NotificationType.WARNING)
            notification.addAction(NotificationAction.createSimple("Trust for Qwen") { notification.expire(); Ui.background(project) { trust(project, root) } })
            notification.addAction(NotificationAction.createSimple("Not now") { notification.expire() })
            notification.addAction(NotificationAction.createSimple("Never for this project") { QwenSettings.get().neverProjects.add(root.toString()); notification.expire() })
            notification.notify(project)
        }
    }
    fun trust(project: Project, path: Path) {
        requireUser(TrustedProjects.isProjectTrusted(project)) { "Trust the project in the IDE first" }
        val root = path.toRealPath()
        requireUser(root.parent != null && root != Path.of(System.getProperty("user.home")).toRealPath()) { "Cannot trust root or home" }
        val settings = QwenSettings.get()
        val rules = settings.trust().read()
        val blocks = Trust.matches(rules, root, Trust::real).filter { it.level == TrustLevel.DO_NOT_TRUST }
        requireUser(blocks.all { it.rule.toAbsolutePath().normalize() == root }) { "A parent or alias blocks this folder; manage that rule outside the plugin" }
        if (blocks.isNotEmpty() && !Ui.confirm(project, "An explicit DO_NOT_TRUST rule blocks this project.", "Remove the block and trust")) return
        val scan = ProjectConfig.scan(root)
        if (scan.isNotEmpty() && !Ui.confirm(project, "Trust activates project configuration at $root:\n\n" + scan.joinToString("\n"), "Trust anyway")) return
        requireUser(TrustedProjects.isProjectTrusted(project) && path.toRealPath() == root && settings.trust().read() == rules && ProjectConfig.scan(root) == scan) { "Configuration changed; retry consent" }
        settings.trust().change(root, TrustLevel.TRUST_FOLDER, rules)
        Ui.later { if (root.toString() !in settings.addedRules) settings.addedRules.add(root.toString()) }
        Ui.changed(project)
    }
    fun revoke(project: Project?, root: Path) {
        val settings = QwenSettings.get()
        settings.trust().change(root, null)
        Ui.later { settings.addedRules.remove(root.toString()) }
        if (Trust.trusted(settings.trust().read(), root, Trust::real) && Ui.confirm(project, "A parent rule still trusts $root. Add DO_NOT_TRUST for this folder?", "Block this folder")) {
            settings.trust().change(root, TrustLevel.DO_NOT_TRUST)
            Ui.later { settings.addedRules.add(root.toString()) }
        }
        Ui.changed(project)
    }
}
class QwenStartup : ProjectActivity { override suspend fun execute(project: Project) { TrustActions.check(project) } }
class QwenTrustListener : TrustedProjectsListener { override fun onProjectTrusted(project: Project) { TrustActions.check(project) } }

object BundleAction {
    fun run(project: Project?) = Ui.background(project) {
        val settings = QwenSettings.get()
        val ctl = settings.workstationctl.takeIf { it.isNotBlank() }?.let(Path::of) ?: AgentConfig.workstationctl(Path.of(settings.launcher)) ?: error("Cannot find workstationctl")
        // Only fixed progress messages are exposed: subprocess output may contain secret material.
        var lines = 0
        Bundles.regenerate(ctl, Path.of(settings.config), Path.of(settings.bundleDirectory), Path.of(settings.currentBundle)) {
            lines++
            if (lines == 1 || lines % 20 == 0) Ui.notify(project, "Bundle generation running ($lines output lines received).")
        }
        Ui.changed(project)
    }
}
class RegenerateAction : AnAction() { override fun actionPerformed(e: AnActionEvent) { BundleAction.run(e.project) } }
