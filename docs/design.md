# Design and API evidence

The plugin separates filesystem and protocol logic from platform UI. `Files.kt` implements strict JSON, atomic replacement and Qwen precedence. `Agent.kt` implements bundle verification, process execution, project scanning, Git worktree discovery and HTTP status. The other Kotlin files provide persisted preferences, notifications, settings, actions and the status widget.

The build pins PyCharm 2026.2.4 (**262.11879.98**). JetBrains' [release metadata](https://data.services.jetbrains.com/products/releases?code=PCP&latest=false&type=release) reports the initial 2026.2 release as **262.8665.309**. Compatibility is limited to branch 262.

| API | Evidence and use |
| --- | --- |
| `ProjectActivity` / `postStartupActivity` | Platform startup hook, invoked on project open; file work is dispatched to a pooled thread. |
| `TrustedProjects.isProjectTrusted(Project)` | Checked in [262 source](https://github.com/JetBrains/intellij-community/blob/pycharm/262.11879.98/platform/platform-impl/src/com/intellij/ide/trustedProjects/TrustedProjects.kt). The Project overload is public; internal Path overloads are not used. |
| `TrustedProjectsListener` | Checked in [262 source](https://github.com/JetBrains/intellij-community/blob/pycharm/262.11879.98/platform/platform-impl/src/com/intellij/ide/trustedProjects/TrustedProjectsListener.kt). Registered as an application listener; overrides `onProjectTrusted(Project)`. |
| `StatusBarWidgetFactory`, `StatusBarWidget.TextPresentation` | [SDK widget documentation](https://plugins.jetbrains.com/docs/intellij/status-bar-widgets.html). Factory and widget IDs match the descriptor. |
| `NotificationGroupManager`, `NotificationAction` | Declared notification group; actionable project trust notification. |
| `Configurable`, `PersistentStateComponent`, `@Service`, `@State`, `@Storage` | Platform settings and persistence; roaming disabled because paths and consent are machine-specific. |
| `ApplicationManager`, `AppExecutorUtil` | Background I/O and scheduled polling; UI updates return to the EDT. |
| `java.net.http.HttpClient` | Standard Java API; no redirect following, credentials or custom TLS trust manager. |

The older `com.intellij.ide.impl.isTrusted` example in the SDK guide is deprecated in 262. It was replaced before implementation verification. No AI Assistant or Air classes are referenced.

Qwen precedence was checked against [trust-precedence.ts](https://github.com/QwenLM/qwen-code/blob/v0.27.1/packages/cli/src/config/trust-precedence.ts) and [path-comparison.ts](https://github.com/QwenLM/qwen-code/blob/v0.27.1/packages/cli/src/config/path-comparison.ts). `TRUST_PARENT` takes the lexical dirname **before** computing realpath variants. Rule depth uses the effective matching variant; an equal-depth block wins. Resolution is pure with an injected realpath function.

[Air settings documentation](https://www.jetbrains.com/help/air/air-in-ides-settings.html) describes a configurable worktree root, currently defaulting to `~/.air/worktrees/temporary`. It is not a versioned 262 contract. Git backlinks provide discovery independent of that setting; no session-to-project public API was established.

Writes use same-directory temporary files and atomic moves without a non-atomic fallback. JSON writes fsync the temporary file. Bundle generation only switches the active link after hash verification. Subprocess output is consumed but notifications expose progress counts instead of raw output, because arbitrary output could contain secrets. This intentionally limits diagnostic detail.

The downloaded SDK's `product-info.json` requires Java 25, and its Kotlin classes carry 2.4 metadata. The build therefore uses JDK 25 and Kotlin 2.4.21. Target binary signatures for notifications, settings and persistence are retained in [api-signatures.txt](api-signatures.txt); startup and widget interfaces were also inspected with JDK 25 `javap`. Compilation and Plugin Verifier provide the remaining binary checks; see the verification report for observed results.
