# KhoiRevanced architecture

KhoiRevanced keeps NexAlloy updates and Android-runtime work on separate merge surfaces.

| Area | Location | Rule |
|---|---|---|
| NexAlloy mirror | `app/`, `stub/`, `morphe-patches*` | Keep modifications minimal. |
| Stable patch API | `runtime-api/` | No Xposed or ART imports. |
| Process agent | `runtime-agent/` | Loads payload DEX and selects native backend. |
| Injector | `native/injector/` | arm64 ptrace and remote `dlopen`; no patch logic. |
| Device UX | `controller/` | Root deployment, profiles and lifecycle. |

## Startup

1. `inject.sh launch youtube` starts the stock package.
2. The controller copies files to the target package's `code_cache/khoirevanced` and writes a PID config owned by its UID.
3. The injector attaches with `ptrace`, maps the path and invokes remote `dlopen` for the agent.
4. The agent creates `DexClassLoader` with host app ClassLoader as parent and calls `AgentBootstrap` from `payload.dex`.
5. Patches run only after `NativeHookBackend` advertises their required capability.

## Porting rule

Do not add `de.robv.android.xposed.*` below `runtime-api/` or `runtime-agent/`. First adapt a NexAlloy patch to `HookBackend`, then register it from `PatchEntry`.

## Current milestone

Injector, DEX bootstrap, process-owned deployment and compatibility API are present. ART method hooking is deliberately disabled until its Android-version test matrix is implemented, so no NexAlloy patch is enabled yet.
