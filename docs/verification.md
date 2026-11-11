# Verification results

Verified on 11 November 2026 on macOS arm64 using Temurin JDK 25, Gradle 9.2.1, Kotlin 2.4.21 and IntelliJ Platform Gradle Plugin 2.20.1.

```text
./gradlew test verifyPlugin buildPlugin
BUILD SUCCESSFUL in 39s
20 actionable tasks: 14 executed, 6 up-to-date

CoreTest: 18 tests, 0 skipped, 0 failures, 0 errors
Plugin family.thespry.qwen.workstation:0.1.0 against PY-262.11879.98: Compatible
```

The command ran with `JAVA_HOME` pointing to the downloaded JDK 25 and `GRADLE_USER_HOME` pointing to a temporary build cache. The standard Gradle wrapper has an official distribution SHA-256 pin.

[Build output](build-output.txt) and [JUnit XML](test-results.xml) are retained with machine-identifying paths and hostname redacted. The HTML test report is generated at `build/reports/tests/test/index.html`. The installable ZIP is `build/distributions/Spry.ai-jetbrains-qwen-trust-0.1.0.zip`.

The suite covers trust precedence and symlink variants; exact-parent ordering; malformed, commented and symlinked trust-file refusal; permissions and entry preservation; ACP preservation and migration; project MCP/hook scanning; both bundle config names and mismatch; failed and successful fake-workstationctl runs; launcher detection; all status phases; config parsing; credential-path exclusion; public HTTP probes; and verified Git worktree backlinks.

The platform build launched its searchable-options process successfully and indexed the plugin configurable. Plugin Verifier reported no deprecated or internal API findings in the final run. It warned that several optional modules listed in the PyCharm distribution layout were absent. These distribution warnings are retained in the build output; the compatibility verdict is still Compatible.

Earlier runs exposed build configuration errors, the target SDK's Java/Kotlin requirements, a deprecated Gson call, compiler-generated widget compatibility bridges, and two macOS `/var` versus `/private/var` test expectations. These were corrected before the final passing run. No legitimate test was removed or weakened.

`ai-guardrails docs audit --path README.md` and the equivalent design-note audit passed with no findings. There is no repository-specific formatter or linter configuration. Kotlin compilation, tests and Plugin Verifier were run. The initial verification ran before Git initialization. Existing IDE metadata and the starter source were preserved locally and excluded from the initial commit. No plugin release or Marketplace publication was created.

## Remaining acceptance work and limitations

Interactive testing with AI Assistant enabled, Air enabled, and both absent has **not** been performed. The descriptor depends only on the platform, but static compatibility does not prove those runtime combinations. Notification buttons, consent dialogs and settings persistence have not been exercised by automated platform UI tests. No live gateway requests or user trust/ACP/bundle changes were made during development.

Run these checks in a 262 IDE with the user's configured agent:

1. Install the ZIP, open a new project and trust it in the IDE. Confirm the Qwen notification appears.
2. Choose Trust. Confirm project MCP servers and hook commands appear in consent when present. Check the realpath entry, then start a fresh AI Chat or Air session and call `search_web` through the client's permission prompt.
3. Confirm `Qwen: ready · tools on` when the gateway and MCP check succeed.
4. Use a disposable bundle fixture to test a mismatch and Regenerate. Confirm the previous bundle remains and the active symlink changes only after successful verification.
5. Use an ACP fixture with the launcher under `mcpServers`. Confirm Fix moves registration while preserving unrelated agents and settings.
6. Repeat in the two client configurations and with neither client enabled. Create an Air worktree, run Find project worktrees and confirm consent applies to that folder alone.

Air's current documentation states a configurable default of `~/.air/worktrees/temporary`; the historical 262 default was not established. Discovery uses verified Git metadata and does not identify which client created a worktree. Newly created worktrees are found on demand or when the project reopens. Sessions without that metadata remain a stated popup limitation.

Raw workstationctl output is not displayed: progress notifications report received output-line counts to avoid exposing secrets. This is a deliberate deviation from the requested raw output stream. Commented trust files are refused for reads as well as writes. Trust-file updates detect concurrent changes immediately before replacement, but cannot eliminate a race with an external writer that does not share a lock. Parent or alias block rules require external management; the plugin only replaces an exact canonical project block after explicit consent.
