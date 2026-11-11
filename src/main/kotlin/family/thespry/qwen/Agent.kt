package family.thespry.qwen

import com.google.gson.JsonElement
import java.nio.file.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.net.URI
import java.net.http.*
import java.time.Duration

object AgentConfig {
    fun parse(text: String): Map<String, String> = buildMap {
        text.lineSequence().forEach { line ->
            val key = line.substringBefore('=').trim()
            if (key in setOf("AGENT_BASE_URL", "AGENT_MODEL") && '=' in line) {
                var value = line.substringAfter('=').trim()
                if (value.length >= 2 && value.first() in "\"'" && value.last() == value.first()) value = value.substring(1, value.length - 1)
                put(key, value)
            }
        }
    }
    fun workstationctl(launcher: Path): Path? {
        val match = Regex("""(?m)^\s*exec\s+"([^"\r\n]+/bin/workstationctl)"\s+agent\s+launch\s+"\${'$'}bundle"""").find(SafeFiles.readString(launcher)) ?: return null
        return Path.of(match.groupValues[1]).takeIf { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}

data class BundleHealth(val id: String, val ok: Boolean, val date: String)
object Bundles {
    fun health(bundle: Path): BundleHealth {
        requireUser(!Files.isSymbolicLink(bundle.resolve("bundle.json"))) { "Refusing symlinked bundle manifest" }
        val manifest = Json.obj(SafeFiles.readString(bundle.resolve("bundle.json")))
        val config = bundle.resolve("qwen/system-settings.json").takeIf { Files.exists(it) } ?: bundle.resolve("qwen/settings.json")
        requireUser(!Files.isSymbolicLink(config) && !Files.isSymbolicLink(bundle.resolve("bundle.json"))) { "Refusing symlinked bundle files" }
        val actual = MessageDigest.getInstance("SHA-256").digest(SafeFiles.readAllBytes(config)).joinToString("") { "%02x".format(it) }
        return BundleHealth(bundle.fileName.toString(), manifest.getAsJsonObject("config_sha256").get("qwen").asString == actual, Files.getLastModifiedTime(bundle.resolve("bundle.json")).toString())
    }
    fun regenerate(ctl: Path, config: Path, directory: Path, current: Path, output: (String) -> Unit): Path {
        requireUser(Files.isExecutable(ctl)) { "workstationctl missing or not executable" }
        requireUser(!Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) { "current-bundle must be a symlink" }
        val oldTarget = if (Files.isSymbolicLink(current)) Files.readSymbolicLink(current) else null
        val destination = directory.resolve("bundle-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
        requireUser(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "Bundle destination already exists; retry later" }
        val process = ProcessBuilder(ctl.toString(), "--config", config.toString(), "agent", "configure", destination.toString()).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(output) }
        requireUser(process.waitFor() == 0) { "Bundle generation failed; active bundle unchanged" }
        requireUser(health(destination).ok) { "Generated bundle failed integrity check; active bundle unchanged" }
        val temporary = current.resolveSibling(".current-bundle-" + java.util.UUID.randomUUID())
        try {
            Files.createSymbolicLink(temporary, destination.toAbsolutePath())
            requireUser((if (Files.isSymbolicLink(current)) Files.readSymbolicLink(current) else null) == oldTarget &&
                (!Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current))) { "Active bundle changed concurrently" }
            Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return destination
    }
}

object ProjectConfig {
    fun scan(root: Path): List<String> = buildList {
        for (relative in listOf(".qwen/settings.json", ".mcp.json")) {
            val file = root.resolve(relative)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue
            // Do not follow project-controlled symlinks, which could expose a credential file.
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.parent)) { add("$relative: symlink; contents cannot be inspected"); continue }
            val obj = runCatching { Json.obj(SafeFiles.readString(file)) }.getOrElse { add("$relative: cannot inspect (invalid JSON or unreadable)"); continue }
            add("$relative activates project configuration")
            obj.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (name, server) ->
                if (server.isJsonObject) {
                    val detail = server.asJsonObject.get("command") ?: server.asJsonObject.get("url") ?: server.asJsonObject.get("httpUrl")
                    add("Server $name: ${detail?.takeIf { it.isJsonPrimitive }?.asString ?: "unspecified command/URL"}")
                }
            }
            fun hooks(node: JsonElement) {
                if (node.isJsonObject) node.asJsonObject.entrySet().forEach { (key, value) ->
                    if (key == "command" && value.isJsonPrimitive) add("Hook: ${value.asString}") else hooks(value)
                } else if (node.isJsonArray) node.asJsonArray.forEach(::hooks)
            }
            obj.get("hooks")?.let(::hooks)
        }
    }
}

data class GatewayStatus(val phase: String, val model: String, val images: Boolean, val active: Int, val mcp: Boolean = false) {
    fun widget(trusted: Boolean) = "Qwen: $phase" + if (mcp && trusted && phase != "unreachable") " · tools on" else ""
    companion object {
        fun parse(text: String): GatewayStatus {
            val obj = Json.obj(text)
            val phase = obj.get("phase").asString
            requireUser(phase in setOf("ready", "starting", "draining", "maintenance", "unavailable")) { "Unknown gateway phase" }
            return GatewayStatus(phase, obj.get("model").asString, obj.get("images").asBoolean, obj.get("active").asInt)
        }
    }
}
object Gateway {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun base(value: String): URI {
        val uri = URI(value.removeSuffix("/").removeSuffix("/v1"))
        requireUser(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) { "Expected an HTTP(S) host URL ending in /v1" }
        return uri
    }
    fun poll(value: String): GatewayStatus {
        val base = base(value)
        val status = client.send(HttpRequest.newBuilder(base.resolve("/_service/status")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        requireUser(status.statusCode() == 200) { "Gateway unavailable" }
        val parsed = GatewayStatus.parse(status.body())
        val mcp = runCatching { client.send(HttpRequest.newBuilder(base.resolve("/mcp")).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 401 }.getOrDefault(false)
        return parsed.copy(mcp = mcp)
    }
}

/** Read Git's worktree backlinks instead of assuming an Air-specific directory. */
object Worktrees {
    fun discover(root: Path): List<Path> {
        var git = root.resolve(".git")
        if (Files.isRegularFile(git)) git = root.resolve(SafeFiles.readString(git).trim().removePrefix("gitdir: ")).normalize()
        if (Files.exists(git.resolve("commondir"))) git = git.resolve(SafeFiles.readString(git.resolve("commondir")).trim()).normalize()
        val entries = git.resolve("worktrees")
        if (!Files.isDirectory(entries)) return emptyList()
        return Files.list(entries).use { stream -> stream.map { entry ->
            runCatching {
                val dotGit = Path.of(SafeFiles.readString(entry.resolve("gitdir")).trim())
                val back = dotGit.parent.resolve(SafeFiles.readString(dotGit).trim().removePrefix("gitdir: ")).toRealPath()
                requireUser(back == entry.toRealPath()) { "Worktree backlink does not match" }
                dotGit.parent.toRealPath()
            }.getOrNull()
        }.filter { it != null && it != root.toRealPath() }.map { it!! }.toList() }
    }
}
