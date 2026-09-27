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
agent dex actually defines the types that gate payload loading.

## What upstream gets from LSPosed, and this project has to own

This is the single most important difference from upstream NexAlloy, and it is
easy to miss because upstream's build files look almost identical.

Upstream compiles against `de.robv.android.xposed:api:82`. That artifact is a
**stub**: every method body is `throw new RuntimeException("Stub!")`. LSPosed
replaces those classes at runtime with a real implementation. So upstream is
genuinely an LSPosed module, and anything the official `XposedHelpers` does is
available to it for free.

KhoiRevanced has no LSPosed, so every behaviour it relies on has to be
supplied locally. Concretely:

| Concern | Upstream NexAlloy | KhoiRevanced |
| --- | --- | --- |
| `de.robv.android.xposed.*` at runtime | injected by LSPosed | Pine's `pine-xposed.jar` |
| `hookMethod` implementation | LSPosed's engine | LSPlant + Dobby, via `XposedBridge.setHookProvider` |
| `static final` field writes | LSPosed's `XposedHelpers` | `runtime-api`'s `StaticFields` |

The third row is the concrete instance of the pattern. Upstream commit
`97e14d5` ("Remove legacy Xposed compatibility shims") deleted its own 1842-line
`XposedHelpers` and switched to the official API explicitly to obtain "the
Xposed Framework's Android 17 static final unmodifiable field workaround". Pine's
copy predates that change — it has no `sun.misc.Unsafe` reference — so on
Android 14+ a `static final` write such as
`SpoofFeaturesPatch` performing `Build.BRAND = "google"` degrades to a plain
reflective write that ART rejects.

`runtime-api`'s `StaticFields` therefore implements the fallback chain itself:
direct reflective write, then clearing `Field.modifiers`' FINAL bit, then a
`sun.misc.Unsafe` write at the field's memory offset. The payload reaches it via
`compileOnly(project(":runtime-api"))`, which is why the classes must be present
in the agent dex.

Note that only the first two fallbacks are exercisable on a desktop JVM:
HotSpot filters `Field.modifiers` out of reflection from JDK 12 and rejects
static fields in `Unsafe.objectFieldOffset` entirely. ART keeps
`objectFieldOffset` working for statics, so the Unsafe path is Android-specific
and has not been verified on a device. `StaticFieldsTest` therefore pins the
contract (write succeeds, or fail with a clear `IllegalAccessException`) rather
than asserting success for `static final` on the host.

The same "own it or lose it" rule applies to anything else the payload reaches
for through the Xposed API. When adding a patch that uses an Xposed helper,
check whether Pine's copy actually implements the behaviour, because upstream
would have received the real thing.

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
