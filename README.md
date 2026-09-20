# KhoiRevanced

KhoiRevanced is a **standalone** root-only Android manager APK. It does **not** depend on LSPosed, an Xposed manager, or Xposed module metadata.

It extracts a restricted shell runtime into app-private storage and runs **allow-listed** actions and bundled `.sh` scripts only after a root manager grants `su`.

Inspired by [NexAlloy](https://github.com/nexalloy/NexAlloy) as motivation for a no-LSPosed workflow — this is an independent clean-room implementation (see [NOTICE.md](NOTICE.md)).

## Target device (developer context)

- OnePlus Ace 6T
- OxygenOS `16.0.10.500` (and related OPlus builds)
- ABI: `arm64-v8a`

`doctor` and the `device-info` script print model / OTA / OxygenOS-related props so you can confirm the phone before running other allow-listed scripts.

## What it does

| Action | Purpose |
|--------|---------|
| `doctor` | Root + device fingerprint (ABI, SDK, model, OOS/ColorOS props) |
| `install` | Verify runtime assets (agent + scripts) after extract |
| `status` | Show runtime paths and allow-lists |
| `script <id>` | Run a **bundled** allow-listed `.sh` (`hello`, `device-info`) |
| `logs` / `stop` | Read runtime log / no-op cleanup note |
| `launch <profile>` | Optional ART attach-agent for an **owned debuggable** test app only |

### Bundled scripts

Scripts live under [`manager/src/main/assets/runtime/scripts/`](manager/src/main/assets/runtime/scripts/):

- `hello` — smoke test under root
- `device-info` — OnePlus / OxygenOS fingerprint dump

The Java layer and `khoirevanced.sh` both enforce the same allow-list. The UI never accepts free-form shell.

## Security boundaries

- Root required; failure is explicit.
- No LSPosed / Xposed hooks or module metadata.
- No arbitrary user-typed shell; only allow-listed actions and script IDs.
- ART `--attach-agent` only after a `DEBUGGABLE` check; non-debuggable targets are refused.
- Does not bypass SELinux, rewrite system properties, modify system partitions, or inject into arbitrary production apps.

> [!IMPORTANT]
> This APK is a **root shell manager + diagnostics**, not a drop-in NexAlloy/ReVanced patch engine. Magisk/Zygisk-style app patching is out of scope for this tree.

## Build

```powershell
.\gradlew.bat :manager:lintDebug :manager:testDebugUnitTest :manager:assembleDebug
```

Debug APK: `manager/build/outputs/apk/debug/manager-debug.apk`.

## On-device flow

1. Install the APK on the Ace 6T (or another arm64 rooted device).
2. Open KhoiRevanced → grant root when your root manager prompts.
3. **Install runtime** → **Check root / doctor** → **Run script: device-info**.
4. (Optional) Point `example-debug.properties` at an app **you own** and build as debuggable, then **Launch example debug profile**.

## License and provenance

Independent root-shell manager and diagnostic agent. It does not bundle NexAlloy/Xposed code or patch payloads. NexAlloy motivated the standalone direction; that does not imply endorsement by its authors.
