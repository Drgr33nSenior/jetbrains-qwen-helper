package family.thespry.qwen

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.*

class QwenConfigurable : Configurable {
    private val fields = linkedMapOf<String, JTextField>()
    private var auto = JCheckBox("Trust for Qwen whenever the IDE trusts a project")
    private var panel: JPanel? = null
    private val status = JTextArea(5, 70).apply { isEditable = false }
    private val rules = DefaultListModel<String>()
    private val ruleList = JList(rules)
    private val names = linkedMapOf(
        "directory" to "Agent directory", "launcher" to "CLI launcher", "acpLauncher" to "ACP launcher",
        "config" to "workstation.conf", "currentBundle" to "Active bundle symlink", "bundleDirectory" to "Generated bundle directory",
        "trustFile" to "Trusted folders JSON", "ideKey" to "IDE credential path (never accessed)",
        "retrievalKey" to "Retrieval credential path (never accessed)", "acpFile" to "JetBrains acp.json",
        "workstationctl" to "workstationctl (blank: detect from CLI launcher)", "agentName" to "Agent display name", "pollSeconds" to "Poll interval (seconds)")
    override fun getDisplayName() = "Qwen Workstation"
    override fun createComponent(): JComponent {
        val form = JPanel(GridLayout(0, 2, 6, 4))
        names.forEach { (key, label) -> form.add(JLabel(label)); form.add(JTextField().also { fields[key] = it }) }
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(form); add(auto); add(JScrollPane(status)); add(JScrollPane(ruleList)) }
        val buttons = JPanel()
        fun button(label: String, action: () -> Unit) { buttons.add(JButton(label).apply { addActionListener { action() } }) }
        button("Refresh status") { refresh() }
        button("Fix agent entry") { Ui.background(null) { QwenSettings.get().acp().fix(); Ui.changed(null); refresh() } }
        button("Regenerate bundle") { BundleAction.run(null) }
        button("Revoke selected plugin rule") {
            val selected = ruleList.selectedValue?.substringBefore(" = ") ?: return@button
            if (selected in QwenSettings.get().addedRules) Ui.background(null) { TrustActions.revoke(null, Path.of(selected)); refresh() }
        }
        body.add(buttons)
        body.add(JTextArea("Actions use saved settings; Apply path changes first.\nAI Assistant has no public agent-registration API. This plugin manages acp.json.\nA private ACP registry via llm.chat.agent.acp.external.registry.url replaces the public registry; it is not configured here.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true })
        panel = JPanel(BorderLayout()).apply { add(JScrollPane(body)) }
        reset(); refresh()
        return panel!!
    }
    private fun values() = QwenSettings.get().let { s -> names.keys.associateWith { key ->
        if (key == "pollSeconds") s.pollSeconds.toString() else Preferences::class.java.getDeclaredField(key).apply { isAccessible = true }.get(s).toString()
    } }
    override fun isModified() = values().any { (key, value) -> fields[key]?.text != value } || auto.isSelected != QwenSettings.get().autoTrust
    override fun reset() { values().forEach { (key, value) -> fields[key]?.text = value }; auto.isSelected = QwenSettings.get().autoTrust }
    override fun apply() {
        val interval = fields.getValue("pollSeconds").text.toIntOrNull() ?: throw ConfigurationException("Poll interval must be a number")
        if (interval < 5) throw ConfigurationException("Poll interval must be at least 5 seconds")
        fields.forEach { (key, field) ->
            if (key !in setOf("agentName", "pollSeconds") && !(key == "workstationctl" && field.text.isBlank())) {
                if (!runCatching { Path.of(field.text).isAbsolute }.getOrDefault(false)) throw ConfigurationException("${names[key]} must be an absolute path")
            }
        }
        if (fields.getValue("agentName").text.isBlank()) throw ConfigurationException("Agent name cannot be empty")
        val settings = QwenSettings.get()
        fields.forEach { (key, field) -> if (key != "pollSeconds") Preferences::class.java.getDeclaredField(key).apply { isAccessible = true }.set(settings, field.text) }
        settings.pollSeconds = interval; settings.autoTrust = auto.isSelected
        refresh()
    }
    private fun refresh() = Ui.background(null) {
        val s = QwenSettings.get()
        val agent = runCatching { s.acp().status() }.getOrDefault("Invalid or unreadable ACP file")
        val bundle = runCatching { Bundles.health(Path.of(s.currentBundle).toRealPath()).let { "${it.id}: ${if (it.ok) "integrity OK" else "SHA-256 mismatch"}; ${it.date}" } }.getOrDefault("Bundle unavailable or invalid")
        val ctl = runCatching { s.workstationctl.ifBlank { AgentConfig.workstationctl(Path.of(s.launcher))?.toString() ?: "not found" } }.getOrDefault("not found")
        val entries = runCatching { s.trust().read().map { (path, level) -> "$path = $level" } }.getOrDefault(listOf("Trust file invalid, commented, or unreadable"))
        Ui.later { if (panel != null) { status.text = "Agent: $agent\nBundle: $bundle\nworkstationctl: $ctl"; rules.clear(); entries.forEach(rules::addElement) } }
    }
    override fun disposeUIResources() { panel = null; fields.clear() }
}
