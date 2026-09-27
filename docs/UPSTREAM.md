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
`app/` codes against, not just the resources.

The good news is that upstream's `app/` port layer uses the same
`io.github.nexalloy.patch {}` + `hookMethod` DSL as this project, so new patch
files are usually copyable rather than a rewrite. Upstream v1.43.0 → v1.44.0
was additive at the port layer: the 10 new files dropped in unchanged, and the
only work was supplying four declarations they referenced but that `app/` had
not caught up with yet (`RestrictQuery`, `CLIENT_INFO_CLASS`,
`BuildClientContextBodyConstructorFingerprint`, `BuildClientContextIsAutomotive`).

So the reliable procedure on a bump is:

1. Move both submodules to the commits `upstream/main` records, and keep them
   clean: `git -C morphe-patches status --porcelain` must be empty.
2. List what `app/` is missing:
   `git diff --name-status upstream/main HEAD -- app/src/main/java`
3. Copy those files across. Before overwriting an existing file, check whether
   this fork has diverged for standalone reasons and merge instead:
   `Compare-Object (Get-Content ours) (Get-Content theirs)`.
4. Compile `:nexalloy-payload:compileDebugKotlin` and resolve the unresolved
   references. Each one is a real breakage, so this is mechanical.
5. Check whether upstream also added a new preference, setting, or resource
   that this fork must surface, and whether any behaviour it relies on comes
   from LSPosed rather than from the extension.

Two things to check specifically on each bump, because they fail at runtime
rather than at compile time:

- **Extension method semantics.** Upstream regularly reworks a method's
  behaviour (v1.43.0 replaced the translucent-navigation feature flags with a
  paint-over approach). Compile success does not mean the port still matches.
- **Xposed API behaviour.** See
  [ARCHITECTURE.md](ARCHITECTURE.md#what-upstream-gets-from-lsposed-and-this-project-has-to-own).
  If a new patch relies on behaviour that lives in LSPosed's `XposedHelpers`,
  Pine's copy will not have it and the feature will fail only on device.

Note that Gradle does not always notice a submodule change on its own, because
the submodule directories are injected as extra source roots rather than being
project dependencies. If a bump appears to have no effect, delete
`app/build` and re-run with `--no-configuration-cache` before concluding
anything.

NexAlloy is GPL-3.0. Keep its notices and release corresponding source for every distributed KhoiRevanced bundle.
