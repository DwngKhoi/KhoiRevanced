# KhoiRevanced

KhoiRevanced is a root-managed Android runtime derived from NexAlloy. The
product is a standalone manager APK that embeds a self-extracting
`KhoiRevanced.sh` runtime. It does not need LSPosed, an Xposed manager, or a
module installation step.

## Runtime model

```text
Manager APK
  └─ assets/KhoiRevanced.sh
       ├─ root controller (`controller/inject.sh`)
       ├─ arm64 ptrace injector
       ├─ ART agent (LSPlant + Dobby inline hooking)
       └─ NexAlloy-derived patch dexpack
```

The hook engine is LSPlant with Dobby as the inline hooker, both vendored under
`third_party/`. Pine's `libpine.so` is deliberately **not** shipped and Pine is
never initialized; only Pine's `de.robv.android.xposed` API classes are used, and
`XposedBridge.setHookProvider` re-routes every `hookMethod` call made by the
patch set into the LSPlant backend.

The manager requests root through `su`, extracts the shell bundle into its
private cache, and invokes:

```text
su -mm -c "sh /data/user/0/com.dwngkhoi.revanced/cache/runtime/KhoiRevanced.sh launch youtube"
```

The controller starts the selected app, finds its main process, injects the
native agent, and records readiness under the target app's private
`code_cache/khoirevanced` directory.

## Project boundaries

- `manager/` — the only installable product APK.
- `runtime-api/` — stable hook/runtime contracts.
- `runtime-agent/` — injected ART agent and JNI boundary.
- `native/injector/` — arm64-v8a ptrace/dlopen injector.
- `controller/` — root shell controller and target profiles.
- `app/` (`:nexalloy-payload`) — build-time NexAlloy compatibility payload
  producer. Its APK is copied into the runtime as a dexpack and is never
  installed.

The payload producer still compiles upstream compatibility interfaces so that
the existing patch set can be migrated incrementally. Those interfaces are
bundled into the runtime payload; the installed manager and root controller do
not depend on LSPosed.

## Upstream

The patch set is tracked as a Git submodule pointing at
[NexAlloy/morphe-patches](https://github.com/NexAlloy/morphe-patches) branch
`nexalloy`, currently **v1.43.0** (`c92e718f8`). `app/` is KhoiRevanced's own
port layer that re-expresses those patches as runtime hooks, so it must be kept
in step with the submodule: when upstream renames or removes an extension entry
point, the corresponding reference in `app/` has to be migrated too, otherwise
`:nexalloy-payload` stops compiling.

Upstream NexAlloy is an LSPosed module: it compiles against
`de.robv.android.xposed:api:82`, which is a stub whose methods all throw, and
relies on LSPosed to supply the real implementation at runtime. This project
has no LSPosed, so the Xposed API surface it needs is supplied locally and any
behaviour beyond "call the hook engine" has to be implemented in
`runtime-api`. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#what-upstream-gets-from-lsposed-and-this-project-has-to-own).

## Build

Requirements:

- Android Studio JBR / Java 17
- Android SDK 37
- NDK `25.2.9519653`
- arm64-v8a target device with a root manager

The documented end-to-end entry point packages the runtime and then builds the
manager:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-manager.ps1
```

`:manager:assembleDebug` fails fast when `dist/KhoiRevanced.sh` is missing or
truncated, so a green manager build always implies a complete payload. Building
the manager alone works only if the bundle already exists:

```powershell
.\gradlew.bat :manager:assembleDebug --no-daemon
```

The generated product is:

```text
manager/build/outputs/apk/debug/manager-debug.apk
```

For a direct root test without installing the manager:

```powershell
adb push .\dist\KhoiRevanced.sh /data/local/tmp/
adb shell "su -c 'sh /data/local/tmp/KhoiRevanced.sh launch youtube'"
```

See [docs/TESTING.md](docs/TESTING.md) for the readiness criteria and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the injection sequence.

## Supported profiles

- `youtube` — `com.google.android.youtube`
- `youtube-music` — `com.google.android.apps.youtube.music`
- `google-photos` — `com.google.android.apps.photos`

The current target is arm64-v8a, matching the OnePlus Ace 6T / OxygenOS 16
development device. Additional ABIs should be added only after the injector,
agent library, and packaging pipeline are tested together.
