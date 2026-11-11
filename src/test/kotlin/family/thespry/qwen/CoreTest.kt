package family.thespry.qwen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.test.*

class CoreTest {
    @TempDir lateinit var dir: Path
    @Test fun `trust precedence table`() {
        data class Case(val rules: Map<String, TrustLevel>, val project: String, val expected: Boolean)
        val folder = TrustLevel.TRUST_FOLDER; val parent = TrustLevel.TRUST_PARENT; val deny = TrustLevel.DO_NOT_TRUST
        val cases = listOf(
            Case(mapOf("/work/app" to folder), "/work/app", true),
            Case(mapOf("/work/app" to folder), "/work/app/src", true),
            Case(mapOf("/work/app" to folder), "/work/apple", false),
            Case(mapOf("/work/app" to parent), "/work/other", true),
            Case(mapOf("/work" to folder, "/work/app" to deny), "/work/app/src", false),
            Case(mapOf("/work/app" to folder, "/work/app/child" to parent, "/work/app/." to deny), "/work/app", false),
            Case(mapOf("/work/App" to folder), "/work/app", false),
            Case(emptyMap(), "/work/app", false),
            Case(mapOf("/work/app" to folder), "/link", true)
        )
        for (case in cases) {
            val resolve: (Path) -> Path? = { if (it.toString() == "/link") Path.of("/work/app") else null }
            val rules = case.rules.mapKeys { Path.of(it.key) }
            assertEquals(case.expected, Trust.trusted(rules, Path.of(case.project), resolve), case.toString())
            assertEquals(case.expected, Trust.trusted(rules.entries.reversed().associate { it.toPair() }, Path.of(case.project), resolve))
        }
    }
    @Test fun `real symlink project resolves`() {
        val root = Files.createDirectory(dir.resolve("root")); val link = Files.createSymbolicLink(dir.resolve("link"), root)
        assertTrue(Trust.trusted(mapOf(root to TrustLevel.TRUST_FOLDER), link, Trust::real))
    }
    @Test fun `trust writes preserve entries and private modes`() {
        val path = dir.resolve("private/trust.json"); val file = TrustFile(path)
        file.change(Path.of("/first"), TrustLevel.TRUST_FOLDER)
        file.change(Path.of("/second"), TrustLevel.DO_NOT_TRUST)
        assertEquals(2, file.read().size)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path.parent))
        file.change(Path.of("/second"), null)
        assertEquals(mapOf(Path.of("/first") to TrustLevel.TRUST_FOLDER), file.read())
    }
    @Test fun `bad or commented trust files remain byte identical`() {
        val path = dir.resolve("trust.json")
        for (text in listOf("{", "// comment\n{}", "{/* comment */}", "[]", "{\"/a\":\"UNKNOWN\"}", "{\"/a\":\"TRUST_FOLDER\",\"/a\":\"DO_NOT_TRUST\"}")) {
            Files.writeString(path, text)
            assertFails { TrustFile(path).change(Path.of("/new"), TrustLevel.TRUST_FOLDER) }
            assertEquals(text, Files.readString(path))
        }
    }
    @Test fun `symlink trust file refused`() {
        val target = Files.writeString(dir.resolve("target"), "{}")
        val link = Files.createSymbolicLink(dir.resolve("trust.json"), target)
        assertFails { TrustFile(link).change(Path.of("/new"), TrustLevel.TRUST_FOLDER) }
        assertEquals("{}", Files.readString(target))
    }
    private fun launcher(): Path = Files.writeString(dir.resolve("qwen-acp"), "#!/bin/sh\nexit 0\n").also { it.toFile().setExecutable(true) }
    @Test fun `ACP migration preserves other keys and agents`() {
        val launcher = launcher(); val path = dir.resolve("acp.json")
        Files.writeString(path, """{"default_mcp_settings":{"x":true},"agent_servers":{"Other":{"command":"other"}},"mcpServers":{"wrong":{"command":"$launcher"},"keep":{"command":"elsewhere"}}}""")
        val file = AcpFile(path, launcher, "Qwen")
        file.fix()
        val obj = Json.obj(Files.readString(path))
        assertTrue(obj.getAsJsonObject("default_mcp_settings").get("x").asBoolean)
        assertTrue(obj.getAsJsonObject("agent_servers").has("Other"))
        assertFalse(obj.getAsJsonObject("mcpServers").has("wrong"))
        assertTrue(obj.getAsJsonObject("mcpServers").has("keep"))
        assertEquals(0, obj.getAsJsonObject("agent_servers").getAsJsonObject("Qwen").getAsJsonObject("env").size())
    }
    @Test fun `ACP invalid input unchanged`() {
        val launcher = launcher(); val path = dir.resolve("acp.json")
        for (text in listOf("{bad", "[]", "null", "{\"agent_servers\":[]}")) {
            Files.writeString(path, text)
            assertFails { AcpFile(path, launcher, "Qwen").fix() }
            assertEquals(text, Files.readString(path))
        }
    }
    @Test fun `scanner identifies servers hooks and unsafe config`() {
        Files.createDirectory(dir.resolve(".qwen"))
        Files.writeString(dir.resolve(".qwen/settings.json"), """{"mcpServers":{"search":{"url":"https://example.test/mcp"},"local":{"command":"server"}},"hooks":{"BeforeTool":[{"hooks":[{"command":"do-something"}]}]}}""")
        Files.writeString(dir.resolve(".mcp.json"), "invalid")
        val results = ProjectConfig.scan(dir)
        assertTrue(results.any { "Server search: https://example.test/mcp" == it })
        assertTrue(results.any { "Hook: do-something" == it })
        assertTrue(results.any { "cannot inspect" in it })
    }
    private fun bundle(root: Path, modern: Boolean): Path {
        Files.createDirectories(root.resolve("qwen"))
        val content = "{\"test\":true}"
        Files.writeString(root.resolve(if (modern) "qwen/system-settings.json" else "qwen/settings.json"), content)
        if (modern) Files.writeString(root.resolve("qwen/settings.json"), "user choices")
        val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        Files.writeString(root.resolve("bundle.json"), """{"config_sha256":{"qwen":"$hash"}}""")
        return root
    }
    @Test fun `integrity supports legacy and system settings but ignores modern user file`() {
        for (modern in listOf(false, true)) {
            val root = bundle(dir.resolve("bundle-$modern"), modern)
            assertTrue(Bundles.health(root).ok)
            Files.writeString(root.resolve(if (modern) "qwen/system-settings.json" else "qwen/settings.json"), "corrupt")
            assertFalse(Bundles.health(root).ok)
        }
    }
    @Test fun `regeneration failure preserves active link and old bundle`() {
        val old = bundle(dir.resolve("old"), true); val current = Files.createSymbolicLink(dir.resolve("current"), old)
        val ctl = Files.writeString(dir.resolve("ctl"), "#!/bin/sh\nexit 1\n").also { it.toFile().setExecutable(true) }
        assertFails { Bundles.regenerate(ctl, dir.resolve("conf"), dir, current) {} }
        assertEquals(old.toRealPath(), current.toRealPath()); assertTrue(Files.exists(old.resolve("bundle.json")))
    }
    @Test fun `regeneration success switches link and keeps old bundle`() {
        val old = bundle(dir.resolve("old"), true); val current = Files.createSymbolicLink(dir.resolve("current"), old)
        val ctl = Files.writeString(dir.resolve("ctl"), "#!/bin/sh\ncp -R '${old}' \"\$5\"\n").also { it.toFile().setExecutable(true) }
        val generated = Bundles.regenerate(ctl, dir.resolve("conf"), dir, current) {}
        assertEquals(generated.toRealPath(), current.toRealPath()); assertTrue(Files.isSymbolicLink(current)); assertTrue(Files.exists(old.resolve("bundle.json")))
    }
    @Test fun `status phases and tools states`() {
        for (phase in listOf("ready", "starting", "draining", "maintenance", "unavailable")) {
            val status = GatewayStatus.parse("""{"phase":"$phase","model":"qwen","images":false,"active":2}""")
            assertEquals("Qwen: $phase", status.widget(true))
            assertEquals("Qwen: $phase · tools on", status.copy(mcp = true).widget(true))
            assertEquals("Qwen: $phase", status.copy(mcp = true).widget(false))
        }
        assertEquals("Qwen: unreachable", GatewayStatus("unreachable", "", false, 0).widget(true))
        assertFails { GatewayStatus.parse("""{"phase":"wrong"}""") }
        assertEquals("https://example.test", Gateway.base("https://example.test/v1").toString())
        assertFails { Gateway.base("https://user:password@example.test/v1") }
    }
    @Test fun `configuration reads only two allowed keys without shell evaluation`() {
        assertEquals(mapOf("AGENT_BASE_URL" to "https://example.test/v1", "AGENT_MODEL" to "Qwen"), AgentConfig.parse("# Comment\nSECRET=ignored\nAGENT_BASE_URL='https://example.test/v1'\nAGENT_MODEL=Qwen"))
    }

    @Test fun `launcher discovery accepts only the contracted exec command`() {
        val bin = Files.createDirectory(dir.resolve("bin"))
        val ctl = Files.writeString(bin.resolve("workstationctl"), "#!/bin/sh\nexit 0\n").also { it.toFile().setExecutable(true) }
        val launcher = Files.writeString(dir.resolve("qwen"), "exec \"$ctl\" agent launch \"\$bundle\" qwen\n")
        assertEquals(ctl, AgentConfig.workstationctl(launcher))
        Files.writeString(launcher, "echo not-a-launcher")
        assertNull(AgentConfig.workstationctl(launcher))
    }
    @Test fun `parent semantics resolve dirname before realpath`() {
        val rules = mapOf(Path.of("/alias/child") to TrustLevel.TRUST_PARENT)
        val resolver: (Path) -> Path? = { when (it.toString()) {
            "/alias" -> Path.of("/actual")
            "/alias/child" -> Path.of("/elsewhere/child")
            else -> null
        } }
        assertTrue(Trust.trusted(rules, Path.of("/actual/project"), resolver))
        assertFalse(Trust.trusted(rules, Path.of("/elsewhere/project"), resolver))
    }
    @Test fun `credential aliases are rejected before reading`() {
        val secret = dir.resolve("ide-mac-key.json")
        // No credential is created or opened: a dangling alias is denied by configured path.
        SafeFiles.credentials = setOf(secret)
        try { assertFails { SafeFiles.readString(secret) } } finally { SafeFiles.credentials = emptySet() }
    }

    @Test fun `HTTP probes use only public endpoints without credentials`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/_service/status") { exchange ->
            requests.add(exchange.requestMethod + " " + exchange.requestURI.path)
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"phase":"ready","model":"qwen","images":false,"active":0}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/mcp") { exchange ->
            requests.add(exchange.requestMethod + " " + exchange.requestURI.path)
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("{}", exchange.requestBody.bufferedReader().readText())
            exchange.sendResponseHeaders(401, -1); exchange.close()
        }
        server.start()
        try {
            val result = Gateway.poll("http://127.0.0.1:${server.address.port}/v1")
            assertTrue(result.mcp)
            assertEquals(listOf("GET /_service/status", "POST /mcp"), requests)
        } finally { server.stop(0) }
    }
    @Test fun `verified Git backlinks discover only related worktrees`() {
        val main = Files.createDirectory(dir.resolve("main"))
        val metadata = Files.createDirectories(main.resolve(".git/worktrees/session"))
        val worktree = Files.createDirectory(dir.resolve("session"))
        Files.writeString(metadata.resolve("gitdir"), worktree.resolve(".git").toString())
        Files.writeString(worktree.resolve(".git"), "gitdir: $metadata")
        assertEquals(listOf(worktree.toRealPath()), Worktrees.discover(main))
        Files.writeString(worktree.resolve(".git"), "gitdir: /unrelated")
        assertTrue(Worktrees.discover(main).isEmpty())
    }
}
