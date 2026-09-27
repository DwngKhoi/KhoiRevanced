# First device test

## Build the manager APK (end to end)

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\build-manager.ps1 -Configuration Debug
```

This runs `tools/package-runtime.ps1` first, which produces the self-extracting
runtime bundle, then builds `:manager` and embeds that bundle as an APK asset.
`:manager:assembleDebug` fails fast if `dist/KhoiRevanced.sh` is missing, so a
green manager build always implies a complete payload.

## Build the runtime bundle on its own

```powershell
.\tools\package-runtime.ps1 -Configuration Debug
```

The output is `dist/`, containing `KhoiRevanced.sh` (self-extracting),
`inject.sh`, `profiles/*.conf` and `payload/arm64-v8a/` with `classes.dex`,
the arm64 injector, the native agent, LSPlant, DexKit and the NexAlloy
dexpack. No APK is installed by this step.

## Inject through adb

Connect an arm64 rooted device with USB debugging, then run:

```powershell
.\tools\deploy-test.ps1 -Profile youtube
```

Equivalent on-device invocation after pushing `dist/`:

```sh
su -c 'sh /data/local/tmp/KhoiRevanced.sh launch youtube'
su -c 'sh /data/local/tmp/KhoiRevanced.sh status youtube'
logcat -s KhoiRevanced
```

## Success criteria

`launch` only reports success when all three of these hold:

1. `status` reports `agent loaded` (the agent is mapped in `/proc/<pid>/maps`).
2. `agent-status.txt` in the target app's
   `code_cache/khoirevanced/cache` reports `state=ready` for that pid.
3. The same file reports `module=nexalloy-loaded`, meaning
   `NexAlloyCompatibilityModule` loaded the dexpack and its patch executor
   reached `patches-applied`.

`state=ready` is therefore only written after the LSPlant backend initialised
(`engine=lsplant` appears in the status detail) **and** the NexAlloy patch set
executed. Logcat includes `Runtime attached to com.google.android.youtube` on
success.

If any stage fails, `agent-stage.txt` records the last stage reached and
`diagnose_failure` dumps the crash logcat buffer into `cache/crash.log`.

## Not device-verified

The host build, native compilation and packaging steps are reproducible on a
workstation. Injection, hook installation and patch application require a
rooted arm64 device; the OnePlus Ace 6T / OxygenOS 16.0.10.500 target has not
been used to validate this milestone. ABI support is `arm64-v8a` only.

