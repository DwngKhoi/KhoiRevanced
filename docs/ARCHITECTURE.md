# Standalone runtime architecture

## Trust boundary

The manager has no privileged Android permission and does not request
`WRITE_EXTERNAL_STORAGE`, overlay access, or an LSPosed service binding. Its
only privileged boundary is a short-lived `su -mm -c` process started after
the user grants root in the root manager.

`RootShell` owns process creation and exit-code handling. `RuntimeAsset` owns
copying the immutable shell bundle from APK assets. Keeping these operations
outside `MainActivity` makes the UI replaceable without changing the runtime
contract.

## Launch sequence

1. Manager runs `id` through `su` and requires both exit code `0` and
   `uid=0`.
2. Manager extracts `KhoiRevanced.sh` into its private cache.
3. The shell controller force-stops the selected target and prepares a
   root-owned runtime under the target's `code_cache`.
4. The controller resolves the target launcher, starts it, and selects only
   the main package process.
5. The arm64 injector loads `libkhoirevanced_agent.so`.
6. The agent waits for `Application`, initializes the native hook backend,
   loads the read-only NexAlloy dexpack, and writes `agent-status.txt`.
7. Manager closes its task only after the canonical ready state is visible.

## Class loading across the two dex layers

The runtime ships two separate dex containers and the boundary between them is
the part most likely to break silently.

- `payload/arm64-v8a/classes.dex` is the **agent**: `runtime-agent` +
  `runtime-api` + kotlin-stdlib + the `de.robv.android.xposed` API from
  `pine-xposed.jar` + the `io.github.libxposed.api` classes.
- `payload/arm64-v8a/nexalloy.dexpack` is the **patch payload**: the
  `:nexalloy-payload` APK, extracted to `nexalloy.apk` on device.

`NexAlloyCompatibilityModule` loads the payload with a `DexClassLoader` whose
parent is the agent's own class loader. Every Xposed type the payload compiles
against is `compileOnly`, so **none of them exist inside the dexpack** — the
agent's `classes.dex` has to supply all of them. `io.github.nexalloy.MainHook`
extends `io.github.libxposed.api.XposedModule`, and the framework interface is
never actually called on this path: `MainHook` reuses the already-created
`Application` instead of the LSPosed branch of `onPackageReady`, and both
`XposedModule` and `XposedInterfaceWrapper` expose public no-argument
constructors.

Because a missing type here is a `NoClassDefFoundError` on device and nothing in
a host build notices, `tools/package-runtime.ps1` asserts after `d8` that the
agent dex actually defines the three types that gate payload loading.

## Hooking model

`XposedBridge.setHookProvider(LsplantXposedHookProvider)` re-routes the legacy
API into `lsplant::Hook`, with Dobby as the inline hooker. LSPlant installs one
ART interception per member, but `XposedBridge.hookMethod` may be called many
times for the same member, so the provider keeps one handle per member and an
ordered callback list behind it. `before` callbacks run in registration order
and stop at the first `returnEarly`; `after` runs over exactly the callbacks
that ran in `before`, in reverse. Several NexAlloy fingerprints genuinely are
hooked more than once (`initializeButtonsFingerprint` three times,
`experimentalBooleanFeatureFlagFingerprint` twice), so this is load-bearing
rather than defensive.

## Why the payload is separate

NexAlloy patch code is still being migrated from its historical compatibility
interfaces. It is therefore built as `:nexalloy-payload`, copied into the
runtime as data, and never treated as the product APK. This keeps the
standalone manager independent from LSPosed installation and lets the patch
layer be replaced without redesigning root process control.
