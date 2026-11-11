package family.thespry.qwen

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions

/** Strict parser: refuse JSONC, duplicate keys and trailing content before any write. */
object Json {
    fun parse(text: String): JsonElement {
        val reader = JsonReader(StringReader(text))
        reader.strictness = Strictness.STRICT
        fun value(): JsonElement = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    requireUser(!obj.has(key)) { "Duplicate JSON key" }
                    obj.add(key, value())
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { a ->
                reader.beginArray(); while (reader.hasNext()) a.add(value()); reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toBigDecimal())
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> error("Invalid JSON")
        }
        val result = value()
        requireUser(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
        return result
    }
    fun obj(text: String): JsonObject = parse(text).also { requireUser(it.isJsonObject) { "Expected JSON object" } }.asJsonObject
    fun text(obj: JsonObject): String = GsonBuilder().setPrettyPrinting().create().toJson(obj) + "\n"
}

object AtomicFiles {
    fun readObject(path: Path): JsonObject {
        requireUser(!Files.isSymbolicLink(path)) { "Refusing a symbolic-link JSON file" }
        return if (Files.exists(path)) Json.obj(SafeFiles.readString(path)) else JsonObject()
    }
    fun write(path: Path, obj: JsonObject, expected: String?, privateDirectory: Boolean = false) {
        SafeFiles.check(path)
        val parent = path.toAbsolutePath().parent
        requireUser(!Files.isSymbolicLink(parent)) { "Refusing a symbolic-link directory" }
        Files.createDirectories(parent)
        if (privateDirectory) Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        val tmp = Files.createTempFile(parent, ".qwen-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { ch ->
                val buffer = ByteBuffer.wrap(Json.text(obj).toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) ch.write(buffer)
                ch.force(true)
            }
            requireUser(!Files.isSymbolicLink(path)) { "Refusing a symbolic-link JSON file" }
            val current = if (Files.exists(path)) SafeFiles.readString(path) else null
            requireUser(current == expected) { "File changed concurrently; retry the action" }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
    }
}

enum class TrustLevel { TRUST_FOLDER, TRUST_PARENT, DO_NOT_TRUST }
data class TrustMatch(val rule: Path, val level: TrustLevel, val depth: Int)

object Trust {
    /** Resolver is injected to keep the precedence calculation pure and table-testable. */
    fun matches(rules: Map<Path, TrustLevel>, project: Path, realpath: (Path) -> Path?): List<TrustMatch> {
        fun variants(p: Path) = listOfNotNull(p.toAbsolutePath().normalize(), realpath(p)?.normalize()).distinct()
        val locations = variants(project)
        return rules.flatMap { (rule, level) ->
            val rulePath = if (level == TrustLevel.TRUST_PARENT) rule.parent ?: rule.root else rule
            variants(rulePath).mapNotNull { effective ->
                if (locations.any { it == effective || it.startsWith(effective) }) TrustMatch(rule, level, if (effective == effective.root) 0 else effective.nameCount) else null
            }
        }.sortedWith(compareByDescending<TrustMatch> { it.depth }.thenByDescending { it.level == TrustLevel.DO_NOT_TRUST })
    }
    fun trusted(rules: Map<Path, TrustLevel>, project: Path, realpath: (Path) -> Path?) =
        matches(rules, project, realpath).firstOrNull()?.level?.let { it != TrustLevel.DO_NOT_TRUST } ?: false
    fun real(p: Path): Path? = runCatching { p.toRealPath() }.getOrNull()
}

class TrustFile(private val path: Path) {
    fun read(): Map<Path, TrustLevel> = decode(AtomicFiles.readObject(path))
    private fun decode(obj: JsonObject): Map<Path, TrustLevel> = obj.entrySet().associate { (key, value) ->
        val p = Path.of(key)
        requireUser(p.isAbsolute && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid trust rule" }
        p to TrustLevel.valueOf(value.asString)
    }
    @Synchronized fun change(project: Path, level: TrustLevel?, expected: Map<Path, TrustLevel>? = null) {
        val before = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) SafeFiles.readString(path.also {
            requireUser(!Files.isSymbolicLink(it)) { "Refusing symbolic-link trust file" }
        }) else null
        val obj = before?.let(Json::obj) ?: JsonObject()
        val rules = decode(obj)
        requireUser(expected == null || rules == expected) { "Trust rules changed during consent; retry the action" }
        if (level == null) obj.remove(project.toString()) else obj.addProperty(project.toString(), level.name)
        AtomicFiles.write(path, obj, before, true)
    }
}

class AcpFile(private val path: Path, private val launcher: Path, private val name: String) {
    fun status(): String {
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) return "Launcher missing or not executable"
        val obj = AtomicFiles.readObject(path)
        if (obj.has("mcpServers")) return "Legacy mcpServers key detected; review Fix"
        return if (obj.getAsJsonObject("agent_servers")?.get(name) == entry()) "Registered" else "Agent missing or points elsewhere"
    }
    private fun entry() = JsonObject().apply { addProperty("command", launcher.toAbsolutePath().toString()); add("args", JsonArray()); add("env", JsonObject()) }
    @Synchronized fun fix() {
        requireUser(Files.isRegularFile(launcher) && Files.isExecutable(launcher)) { "Install an executable ACP launcher first" }
        requireUser(!Files.isSymbolicLink(path)) { "Refusing symbolic-link ACP file" }
        val before = if (Files.exists(path)) SafeFiles.readString(path) else null
        val obj = before?.let(Json::obj) ?: JsonObject()
        val agents = if (obj.has("agent_servers")) obj.getAsJsonObject("agent_servers") else JsonObject().also { obj.add("agent_servers", it) }
        if (obj.has("mcpServers")) {
            val legacy = obj.getAsJsonObject("mcpServers")
            legacy.entrySet().filter { (_, v) -> v.isJsonObject && v.asJsonObject.get("command")?.let { it.isJsonPrimitive && it.asString == launcher.toAbsolutePath().toString() } == true }
                .map { it.key }.forEach(legacy::remove)
            if (legacy.size() == 0) obj.remove("mcpServers")
        }
        agents.add(name, entry())
        AtomicFiles.write(path, obj, before)
    }
}

/** Deny credential aliases before opening any plugin-managed input. */
object SafeFiles {
    @Volatile var credentials: Set<Path> = emptySet()
    fun check(path: Path) {
        val resolved = runCatching { path.toRealPath() }.getOrDefault(path.toAbsolutePath().normalize())
        requireUser(listOf(path, resolved).none { it.fileName?.toString() in setOf("ide-mac-key.json", "retrieval-key") } &&
            credentials.none { it.toAbsolutePath().normalize() == resolved || runCatching { it.toRealPath() == resolved || Files.isSameFile(it, path) }.getOrDefault(false) }) { "Credential paths cannot be accessed" }
    }
    fun readString(path: Path): String { check(path); return Files.readString(path) }
    fun readAllBytes(path: Path): ByteArray { check(path); return Files.readAllBytes(path) }
}

class UserError(message: String) : IllegalArgumentException(message)
inline fun requireUser(condition: Boolean, message: () -> String) {
    if (!condition) throw UserError(message())
}
