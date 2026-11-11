package family.thespry.qwen

import com.intellij.openapi.components.*
import java.nio.file.Path

class Preferences {
    var directory = Path.of(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: Path.of(System.getProperty("user.home"), ".config").toString(), "workstation-agent").toString()
    var launcher = "$directory/qwen"
    var acpLauncher = "$directory/qwen-acp"
    var config = "$directory/workstation.conf"
    var currentBundle = "$directory/current-bundle"
    var bundleDirectory = directory
    var trustFile = "$directory/qwen-trustedFolders.json"
    // These are display-only paths; no operation opens these files.
    var ideKey = "$directory/ide-mac-key.json"
    var retrievalKey = "$directory/retrieval-key"
    var acpFile = Path.of(System.getProperty("user.home"), ".jetbrains", "acp.json").toString()
    var workstationctl = ""
    var agentName = "Qwen (Gr33nstein)"
    var pollSeconds = 30
    var autoTrust = false
    var neverProjects = mutableListOf<String>()
    var addedRules = mutableListOf<String>()
    fun trust() = TrustFile(Path.of(trustFile))
    fun acp() = AcpFile(Path.of(acpFile), Path.of(acpLauncher), agentName)
}
@Service(Service.Level.APP)
@State(name = "QwenWorkstation", storages = [Storage(value = "qwen-workstation.xml", roamingType = RoamingType.DISABLED)])
class QwenSettings : PersistentStateComponent<Preferences> {
    private var preferences = Preferences()
    override fun getState(): Preferences {
        SafeFiles.credentials = setOf(Path.of(preferences.ideKey), Path.of(preferences.retrievalKey))
        return preferences
    }
    override fun loadState(state: Preferences) { preferences = state }
    companion object { fun get() = service<QwenSettings>().state }
}
