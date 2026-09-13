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
       ├─ arm64 injector
       ├─ ART agent + Pine runtime
       └─ NexAlloy-derived patch dexpack
```

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

## Build

Requirements:

- Android Studio JBR / Java 17
- Android SDK 37
- NDK `25.2.9519653`
- arm64-v8a target device with a root manager

Build the manager when an existing `dist/KhoiRevanced.sh` is available:

```powershell
.\gradlew.bat :manager:assembleDebug --no-daemon
```

Build the complete runtime bundle and embed it in the manager APK:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-manager.ps1
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

## Supported profiles

- `youtube` — `com.google.android.youtube`
- `youtube-music` — `com.google.android.apps.youtube.music`
- `google-photos` — `com.google.android.apps.photos`

The current target is arm64-v8a, matching the OnePlus Ace 6T / OxygenOS 16
development device. Additional ABIs should be added only after the injector,
agent library, and packaging pipeline are tested together.
