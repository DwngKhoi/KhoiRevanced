# KhoiRevanced

KhoiRevanced is a root-only Android manager with a bundled, restricted shell runtime and an `arm64-v8a` **diagnostic ART agent**. It is standalone: it does not depend on LSPosed, an Xposed manager, or Xposed module metadata.

## Current scope

- The manager requests root with `su`, extracts its runtime into private app storage, and invokes only allow-listed actions: `doctor`, `install`, `status`, `logs`, `stop`, `launch <profile>`.
- The native `libkhoirevanced_agent.so` is packaged for `arm64-v8a` and exposes `Agent_OnAttach` / `Agent_OnLoad` lifecycle entry points for ART/JVMTI diagnostics.
- Launch uses Android's `am start-activity --attach-agent` only after strict checks for root, ABI, SDK, agent file and an owned **debuggable** test target.
- The bundled `example-debug` profile is a template; its package/activity are placeholders and cannot target any production app.

> [!IMPORTANT]
> Android's supported ART Tooling Interface permits agent attachment only to apps marked `android:debuggable="true"`. KhoiRevanced deliberately refuses non-debuggable targets. It does not bypass this requirement, alter SELinux/system properties, modify system partitions, hook arbitrary methods, or change another app's data/entitlements.

## Configure your test app

Edit [`example-debug.properties`](manager/src/main/assets/runtime/profiles/example-debug.properties) with an app you own and can build as debuggable:

```properties
id=example-debug
package=com.example.khoirevanced.target
activity=com.example.khoirevanced.target/.MainActivity
abi=arm64-v8a
min_sdk=27
debuggable_required=true
agent_library=lib/arm64-v8a/libkhoirevanced_agent.so
```

Then build/install KhoiRevanced, grant root, choose **Install runtime**, then **Launch example debug profile**. Examine the in-app runtime output and `logcat` tag `KhoiRevancedAgent`.

## Build

```powershell
.\gradlew.bat :manager:lintDebug :manager:testDebugUnitTest :manager:assembleDebug
```

The resulting debug APK is at `manager/build/outputs/apk/debug/manager-debug.apk`.

## Security boundaries

- No broad storage permission is requested.
- The UI never accepts user-provided shell fragments; profile IDs are allow-listed.
- Runtime artifacts are restricted to `/data/local/tmp/khoirevanced` and app-private storage.
- Root is required and failure is explicit; no fallback injection mechanism exists.

## License and provenance

This is an independent root-shell manager and diagnostic agent implementation. It does not bundle NexAlloy/Xposed code or metadata. NexAlloy is acknowledged as the project that motivated this reimplementation; this does not imply endorsement by its authors.