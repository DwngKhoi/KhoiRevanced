# First device test

## Build bundle

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\package-runtime.ps1 -Configuration Debug
```

The output is `dist/`, containing only a shell controller, `classes.dex`, an
arm64 injector and the native agent. No APK is installed.

## Inject through adb

Connect an arm64 rooted device with USB debugging, then run:

```powershell
.\tools\deploy-test.ps1 -Profile youtube
```

Equivalent on-device invocation after pushing `dist/`:

```sh
su -c /data/local/tmp/khoirevanced-test/inject.sh launch youtube
su -c /data/local/tmp/khoirevanced-test/inject.sh status youtube
logcat -s KhoiRevanced
```

Success criteria for this bootstrap milestone:

1. `status` reports `agent loaded`.
2. `agent-status.txt` reports `state=ready` from the target app's
   `code_cache/khoirevanced/cache` directory.
3. Logcat includes `Runtime attached to com.google.android.youtube`.

`state=ready` currently means injection and DEX/JNI bootstrap passed. It does
not yet mean a NexAlloy feature patch was applied; the ART method-hook backend
is the next milestone.
