# Qwen Workstation

A local JetBrains companion for Qwen Code 0.27.1. It manages folder trust, a custom ACP agent entry, bundle integrity and gateway status. AI Assistant or Air provides the chat and tool-permission UI. Neither plugin is a dependency.

The target is PyCharm 2026.2.4, build **262.11879.98**, with compatibility restricted to `262`–`262.*`. The code depends only on the IntelliJ platform. This workstation layout requires macOS or Linux with POSIX permissions, symlinks and atomic rename support.

## Build and install

Use JDK 25 for the build (required by the target platform):

```sh
./gradlew test verifyPlugin buildPlugin
```

The ZIP appears in `build/distributions`. In a 262 IDE, open **Settings → Plugins → gear menu → Install Plugin from Disk**, select the ZIP and restart. No Marketplace publication is configured. The existing `src/Main.java` IDE starter is preserved and is outside Gradle's source sets.

Open **Settings → Tools → Qwen Workstation**. Review the detected paths, then Apply before using Fix or Regenerate. The defaults use `${XDG_CONFIG_HOME:-~/.config}/workstation-agent` and `~/.jetbrains/acp.json`. Every path is configurable. Credential paths are labels and exclusion rules, never input files. The workstationctl field can be left blank to detect the quoted executable in the CLI launcher's `exec` line.

## Use

- Trust a project in the IDE. If Qwen does not trust it, choose **Trust for Qwen** in the notification. Review any project MCP servers and hooks before confirming. Start a **new AI Chat or Air session** after a change.
- Click the status widget for gateway details, project trust, bundle health and agent registration. Tools are on only when the status endpoint responds, the MCP probe returns 401 and the project matches Qwen's trust rules.
- Use **Fix agent entry** to register the configured launcher under `agent_servers`. It removes matching legacy launcher entries from `mcpServers` while retaining unrelated entries. Install or repair a missing launcher yourself; the plugin does not write launchers.
- Use **Regenerate bundle** to run workstationctl, verify the output and atomically switch the active symlink. Old bundles and session data remain. A failed command can leave its incomplete output directory; the plugin retains it and leaves the active link unchanged.
- **Revoke** removes the exact rule. If a parent still grants trust, the plugin offers an explicit project block. The settings list permits revocation only for entries recorded as added by this plugin.

Trust files containing comments, invalid data or symlinks are refused. Resolve these outside the plugin before attempting a change. Atomic replacements detect changes made before the final check; Qwen does not participate in a shared lock, so simultaneous external writes cannot be fully serialized. Avoid editing the trust file in the CLI while changing it in the plugin.

Auto-trust is off by default. Enabling it permits writes when the IDE trusts a project, but project configuration still requires consent. “Never for this project” suppresses notifications and auto-trust; the widget's explicit Trust action remains available.

## Air worktrees

Current JetBrains documentation identifies `~/.air/worktrees/temporary` as the default and says the root is configurable under **AI → Air → Worktree**. That documentation is dated November 2026; it does not establish the default for every 262 Air plugin version.

The plugin does not assume this location. It discovers worktrees through Git administration files and verifies each backlink. On project open, or through **Find project worktrees**, it offers each untrusted worktree of a Qwen-trusted project separately. Consent scans that worktree's configuration. It never trusts the shared worktree parent. The popup explains that sessions without reliable Git metadata cannot be discovered through a public Air API. Newly created worktrees require another discovery action or project reopen.

## Boundaries

Only `GET /_service/status` and an unauthenticated `POST /mcp` reach the configured gateway. Redirects are disabled, each request has a five-second timeout, and TLS verification uses the JVM defaults. No telemetry, analytics, ACP protocol client or chat UI is included. Trust writes use mode 0600 and the containing directory uses 0700.

AI Assistant offers no public API for inserting agents into its own Agents list. This plugin manages `acp.json`; it does not use the internal agents-catalog extension point. A private ACP registry can be selected with `llm.chat.agent.acp.external.registry.url`, but it **replaces** the public registry. This is documentation only.

See [design and API evidence](docs/design.md) and [verification results](docs/verification.md). Interactive AI Assistant/Air acceptance requires the user's configured gateway and a fresh session.
