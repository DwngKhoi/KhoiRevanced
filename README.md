# KhoiRevanced

A root-only Android manager that embeds and runs a restricted shell runtime. It is a **standalone APK**: it does not depend on LSPosed, an Xposed manager, or Xposed module metadata.

## MVP scope

- Android manager APK requests root through `su`.
- The APK extracts a bundled shell script to app-private storage and invokes an allow-listed action (`doctor`, `install`, `status`, `stop`).
- The script performs environment diagnostics and manages only its own `/data/local/tmp/khoirevanced` directory.
- It intentionally performs **no app-process injection, hook, target profile, ad blocking, or entitlement alteration**. Those require a separately reviewed, device-tested runtime design.

## Supported baseline

- `arm64-v8a` Android device with a root manager that provides `su`.
- Development target: OnePlus Ace 6T / OxygenOS 16.0.10.500.
- `minSdk 27`, `targetSdk 36`, `compileSdk 36`.

## Build

```powershell
.\gradlew.bat :manager:assembleDebug
```

Install `manager/build/outputs/apk/debug/manager-debug.apk`, launch it and press **Check root**. Your root manager must explicitly grant the request.

## Security boundaries

- No broad storage permission is requested.
- UI actions are a fixed allow-list; user-provided shell fragments are never accepted.
- The runtime fails when it is not root and changes only its dedicated runtime directory.
- The APK does not attempt to bypass SELinux, modify system partitions, or inject any process.

## License and provenance

This clean implementation does not copy source code from NexAlloy. It is independently structured as a root shell manager. NexAlloy is acknowledged as the project that motivated this reimplementation; no NexAlloy/Xposed code or metadata is bundled here.