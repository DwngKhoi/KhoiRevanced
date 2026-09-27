# Keeping up with NexAlloy

`upstream` is the unmodified NexAlloy remote; `khoirevanced/main` is the fork branch.

The remote is not configured by a fresh clone. Add it once:

```powershell
git remote add upstream https://github.com/nexalloy/NexAlloy.git
git fetch upstream
```

Then:

```powershell
.\tools\sync-upstream.ps1
.\tools\sync-upstream.ps1 -Merge -UpdateSubmodules
.\gradlew.bat :runtime-api:testDebugUnitTest :runtime-agent:assembleDebug
```

Keep custom work in `runtime-*`, `native/`, `controller/`, `tools/`, and `docs/`. A required NexAlloy modification must be a narrow `port:` commit, separate from upstream merges.

## What a submodule bump breaks

`:nexalloy-payload` compiles `morphe-patches` sources directly, and `app/`
re-implements the patches as runtime hooks against the extension entry points
those sources expose. Bumping the submodule therefore changes the *contract*
`app/` codes against, not just the resources. Upstream v1.43.0 → v1.44.0 touched
135 extension files and renamed or removed entry points that `app/` calls, so
expect `:nexalloy-payload:compileDebugKotlin` to fail until each affected
reference in `app/` is migrated. Iterate on the compiler errors; they are
reliable because every unresolved reference is a real breakage.

Two things to check specifically on each bump, because they fail at runtime
rather than at compile time:

- **Extension method semantics.** Upstream regularly reworks a method's
  behaviour (v1.43.0 replaced the translucent-navigation feature flags with a
  paint-over approach). Compile success does not mean the port still matches.
- **Xposed API behaviour.** See
  [ARCHITECTURE.md](ARCHITECTURE.md#what-upstream-gets-from-lsposed-and-this-project-has-to-own).
  If a new patch relies on behaviour that lives in LSPosed's `XposedHelpers`,
  Pine's copy will not have it and the feature will fail only on device.

NexAlloy is GPL-3.0. Keep its notices and release corresponding source for every distributed KhoiRevanced bundle.
